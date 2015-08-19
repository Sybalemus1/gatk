package org.broadinstitute.hellbender.tools.dataflow.pipelines;

import com.google.api.services.genomics.model.Reference;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.transforms.*;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;
import com.google.cloud.dataflow.sdk.values.PCollectionView;
import htsjdk.samtools.SAMFileHeader;
import htsjdk.samtools.SAMSequenceDictionary;
import org.broadinstitute.hellbender.cmdline.Argument;
import org.broadinstitute.hellbender.cmdline.ArgumentCollection;
import org.broadinstitute.hellbender.cmdline.CommandLineProgramProperties;
import org.broadinstitute.hellbender.cmdline.StandardArgumentDefinitions;
import org.broadinstitute.hellbender.cmdline.argumentcollections.IntervalArgumentCollection;
import org.broadinstitute.hellbender.cmdline.argumentcollections.OpticalDuplicatesArgumentCollection;
import org.broadinstitute.hellbender.cmdline.argumentcollections.OptionalIntervalArgumentCollection;
import org.broadinstitute.hellbender.cmdline.programgroups.DataFlowProgramGroup;
import org.broadinstitute.hellbender.tools.dataflow.transforms.bqsr.ApplyBQSRTransform;
import org.broadinstitute.hellbender.engine.dataflow.DoFnWLog;
import org.broadinstitute.hellbender.engine.dataflow.*;
import org.broadinstitute.hellbender.engine.dataflow.datasources.*;
import org.broadinstitute.hellbender.engine.dataflow.transforms.composite.AddContextDataToRead;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.ApplyBQSRArgumentCollection;
import org.broadinstitute.hellbender.tools.dataflow.transforms.bqsr.BaseRecalOutput;
import org.broadinstitute.hellbender.tools.dataflow.transforms.bqsr.BaseRecalibrationArgumentCollection;
import org.broadinstitute.hellbender.tools.dataflow.transforms.bqsr.BaseRecalibratorTransform;
import org.broadinstitute.hellbender.tools.dataflow.transforms.markduplicates.MarkDuplicates;
import org.broadinstitute.hellbender.utils.IntervalUtils;
import org.broadinstitute.hellbender.utils.SequenceDictionaryUtils;
import org.broadinstitute.hellbender.utils.SimpleInterval;
import org.broadinstitute.hellbender.utils.Utils;
import org.broadinstitute.hellbender.utils.dataflow.SmallBamWriter;
import org.broadinstitute.hellbender.utils.read.GATKRead;
import org.broadinstitute.hellbender.utils.read.markduplicates.OpticalDuplicateFinder;

import java.util.List;
import java.util.Map;


@CommandLineProgramProperties(
        summary = "Takes aligned reads (likely from BWA) and runs MarkDuplicates and BQSR. The final result is analysis-ready reads.",
        oneLineSummary = "Takes aligned reads (likely from BWA) and runs MarkDuplicates and BQSR. The final result is analysis-ready reads.",
        usageExample = "Hellbender ReadsPreprocessingPipeline -I single.bam -R referenceAPIName -BQSRKnownVariants variants.vcf -O output.bam",
        programGroup = DataFlowProgramGroup.class
)

/**
 * ReadsPreprocessingPipeline is our standard pipeline that takes aligned reads (likely from BWA) and runs MarkDuplicates
 * and BQSR. The final result is analysis-ready reads.
 */
public class ReadsPreprocessingPipeline extends DataflowCommandLineProgram {
    private static final long serialVersionUID = 1L;

    @Argument(doc = "uri for the input bam, either a local file path or a gs:// bucket path",
            shortName = StandardArgumentDefinitions.INPUT_SHORT_NAME, fullName = StandardArgumentDefinitions.INPUT_LONG_NAME,
            optional = false)
    protected String bam;

    @Argument(doc = "the output bam", shortName = StandardArgumentDefinitions.OUTPUT_SHORT_NAME,
            fullName = StandardArgumentDefinitions.OUTPUT_LONG_NAME, optional = false)
    protected String output;

    @Argument(doc = "the reference URL, starting with " + RefAPISource.URL_PREFIX, shortName = StandardArgumentDefinitions.REFERENCE_SHORT_NAME,
            fullName = StandardArgumentDefinitions.REFERENCE_LONG_NAME, optional = false)
    protected String referenceURL;

    @Argument(doc = "the known variants", shortName = "BQSRKnownVariants", fullName = "baseRecalibrationKnownVariants", optional = true)
    protected List<String> baseRecalibrationKnownVariants;

    @ArgumentCollection
    protected OpticalDuplicatesArgumentCollection opticalDuplicatesArgumentCollection = new OpticalDuplicatesArgumentCollection();

    @ArgumentCollection
    protected IntervalArgumentCollection intervalArgumentCollection = new OptionalIntervalArgumentCollection();

    @Override
    protected void setupPipeline( Pipeline pipeline ) {
        if (!RefAPISource.isApiSourceUrl(referenceURL)) {
            throw new UserException.CouldNotReadInputFile("Only API reference names are supported for now (start with " + RefAPISource.URL_PREFIX + ")");
        }
        // Load the reads.
        final ReadsDataflowSource readsDataflowSource = new ReadsDataflowSource(bam, pipeline);
        final SAMFileHeader readsHeader = readsDataflowSource.getHeader();
        final List<SimpleInterval> intervals = intervalArgumentCollection.intervalsSpecified() ? intervalArgumentCollection.getIntervals(readsHeader.getSequenceDictionary())
                : IntervalUtils.getAllIntervalsForReference(readsHeader.getSequenceDictionary());

        final PCollectionView<SAMFileHeader> headerSingleton = ReadsDataflowSource.getHeaderView(pipeline, readsHeader);
        final PCollection<GATKRead> initialReads = readsDataflowSource.getReadPCollection(intervals);

        final OpticalDuplicateFinder finder = opticalDuplicatesArgumentCollection.READ_NAME_REGEX != null ?
            new OpticalDuplicateFinder(opticalDuplicatesArgumentCollection.READ_NAME_REGEX, opticalDuplicatesArgumentCollection.OPTICAL_DUPLICATE_PIXEL_DISTANCE, null) : null;
        final PCollectionView<OpticalDuplicateFinder> finderPcolView = pipeline.apply(Create.of(finder)).apply(View.<OpticalDuplicateFinder>asSingleton());

        // Apply MarkDuplicates to produce updated GATKReads.
        final PCollection<GATKRead> markedReads = initialReads.apply(new MarkDuplicates(headerSingleton, finderPcolView));

        // Load the Variants and the Reference and join them to reads.
        final VariantsDataflowSource variantsDataflowSource = new VariantsDataflowSource(baseRecalibrationKnownVariants, pipeline);

        String referenceName = RefAPISource.getReferenceSetID(referenceURL);
        final RefAPISource ref = RefAPISource.getInstance();
        Map<String, Reference> referenceMap = ref.getReferenceNameToReferenceTable(pipeline.getOptions(), referenceName);
        final Map<String, String> referenceNameToIdTable = ref.getReferenceNameToIdTableFromMap(referenceMap);

        // Use the BQSR_REFERENCE_WINDOW_FUNCTION so that the reference bases required by BQSR for each read are fetched
        RefAPIMetadata refAPIMetadata = new RefAPIMetadata(referenceName, referenceNameToIdTable, BaseRecalibratorDataflow.BQSR_REFERENCE_WINDOW_FUNCTION);

        final PCollection<KV<GATKRead, ReadContextData>> readsWithContext = AddContextDataToRead.add(markedReads, refAPIMetadata, variantsDataflowSource);

        // BQSR.
        // default arguments are best practice.
        BaseRecalibrationArgumentCollection BRAC = new BaseRecalibrationArgumentCollection();
        final SAMSequenceDictionary readsDictionary = readsHeader.getSequenceDictionary();
        final SAMSequenceDictionary refDictionary = ref.getReferenceSequenceDictionaryFromMap(referenceMap, readsDictionary);
        checkSequenceDictionaries(refDictionary, readsDictionary);
        PCollectionView<SAMSequenceDictionary> refDictionaryView = pipeline.apply(Create.of(refDictionary)).setName("refDictionary").apply(View.asSingleton());
        final PCollection<BaseRecalOutput> recalibrationReports = readsWithContext.apply(new BaseRecalibratorTransform(headerSingleton, refDictionaryView, BRAC));
        final PCollectionView<BaseRecalOutput> mergedRecalibrationReport = recalibrationReports.apply(View.<BaseRecalOutput>asSingleton());

        final ApplyBQSRArgumentCollection applyArgs = new ApplyBQSRArgumentCollection();
        final PCollection<GATKRead> finalReads = markedReads.apply(new ApplyBQSRTransform(headerSingleton, mergedRecalibrationReport, applyArgs));
        SmallBamWriter.writeToFile(pipeline, finalReads, readsHeader, output);
    }

    private void checkSequenceDictionaries(final SAMSequenceDictionary refDictionary, SAMSequenceDictionary readsDictionary) {
        Utils.nonNull(refDictionary);
        Utils.nonNull(readsDictionary);
        SequenceDictionaryUtils.validateDictionaries("reference", refDictionary, "reads", readsDictionary, true, null);
    }

}
