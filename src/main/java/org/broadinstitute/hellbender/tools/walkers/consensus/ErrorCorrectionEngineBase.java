package org.broadinstitute.hellbender.tools.walkers.consensus;

import htsjdk.samtools.Cigar;
import htsjdk.samtools.CigarElement;
import htsjdk.samtools.CigarOperator;
import htsjdk.samtools.SAMFileHeader;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.broadinstitute.hellbender.cmdline.argumentcollections.ReferenceInputArgumentCollection;
import org.broadinstitute.hellbender.engine.AlignmentContext;
import org.broadinstitute.hellbender.engine.ReferenceContext;
import org.broadinstitute.hellbender.exceptions.UserException;
import org.broadinstitute.hellbender.tools.walkers.mutect.consensus.DuplicateSet;
import org.broadinstitute.hellbender.utils.*;
import org.broadinstitute.hellbender.utils.downsampling.DownsamplingMethod;
import org.broadinstitute.hellbender.utils.haplotype.Haplotype;
import org.broadinstitute.hellbender.utils.locusiterator.AlignmentStateMachine;
import org.broadinstitute.hellbender.utils.locusiterator.LocusIteratorByState;
import org.broadinstitute.hellbender.utils.pileup.PileupElement;
import org.broadinstitute.hellbender.utils.pileup.ReadPileup;
import org.broadinstitute.hellbender.utils.read.*;
import org.broadinstitute.hellbender.tools.walkers.haplotypecaller.LocationAndAlleles;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.broadinstitute.hellbender.utils.gcs.BucketUtils.logger;

public abstract class ErrorCorrectionEngineBase implements ErrorCorrector {
    protected ReferenceInputArgumentCollection referenceArguments;

    protected SAMFileHeader header;
    final Set<String> samplesList;


    public static final String CONSENSUS_READ_TAG = "CR";
    private static final int NUM_POSSIBLE_BASES = 4;
    private static final int MINIMUM_REQUIRED_SET_SIZE = 2;

    // Is it possible to write code such that the subclass engines don't have to call its own constructor (and use this one instead)?
    // https://stackoverflow.com/questions/22470316/does-a-subclass-need-to-have-a-constructor
    public ErrorCorrectionEngineBase(final SAMFileHeader header, final ReferenceInputArgumentCollection referenceArguments, final SAMFileGATKReadWriter outputWriter){
        this.header = header;
        this.samplesList = ReadUtils.getSamplesFromHeader(header);
        this.referenceArguments = referenceArguments;

    }

    /**
     *
     * Main entry into the engine:
     *
     * Module 1: Find the consensus read
     * Module 2: Update qualities
     */
    @Override
    public Collection<GATKRead> correctErrorsAndUpdateQualities(final DuplicateSet duplicateSet, final ReferenceContext referenceContext){
        final int readLength = duplicateSet.getReads().get(0).getLength();

        // Group the reads by the strand (F1R2/F2R1) and the read number (Read1/Read2)
        // Idea: since there are only four keys, we could write them out instead
        final TreeMap<StrandAndReadNum, List<GATKRead>> duplicateSetByStrandReadNum = duplicateSet.getReads().stream()
                .collect(Collectors.groupingBy(r -> getReadStrandReadNum(r), () -> new TreeMap<>(), Collectors.toList()));
        final TreeMap<StrandAndReadNum, GATKRead> consensusReads = new TreeMap<>();

        for ( Map.Entry<StrandAndReadNum, List<GATKRead>> groupAndReads : duplicateSetByStrandReadNum.entrySet()) {
            final StrandAndReadNum strandAndReadNum = groupAndReads.getKey();
            final List<GATKRead> reads = groupAndReads.getValue();

            if (filterReadSet(reads)){
                // TODO: collect these events somewhere
                continue;
            }

            // LocusIterator requires that the reads be sorted
            reads.sort(new ReadCoordinateComparator(header));
            final boolean hasIndelReads = reads.stream().anyMatch(r -> CigarUtils.containsIndels(r.getCigar()));

            // Step 1: Get Consensus Read
            final GATKRead consensusRead = getConsensusRead(reads, referenceContext, hasIndelReads);

            // Step 2: Update quals
            updateBaseQuals(consensusRead, reads, samplesList); // START HERE getting destroyed by softclips
            consensusReads.put(strandAndReadNum, consensusRead);
        }

        return consensusReads.values();
    }

    private GATKRead getConsensusRead(final List<GATKRead> reads, final ReferenceContext referenceContext, final boolean containsIndelReads) {
        if (containsIndelReads) {
            return getIndelConsensusRead(reads, referenceContext);
        } else {
            // No indels in the duplicate set. This isn't quite right, but it'll do for now.
            final GATKRead consensusRead = reads.get(0).deepCopy();
            consensusRead.setCigar(new Cigar(Arrays.asList(new CigarElement(consensusRead.getLength(), CigarOperator.M))));
            return consensusRead;
        }
    }

    // TODO: think---do I need a referenceContext?
    protected abstract GATKRead getIndelConsensusRead(final List<GATKRead> reads, final ReferenceContext referenceContext);


    @Override
    public boolean filterDuplicateSet(final DuplicateSet duplicateSet) {
        return false;
    }

    private boolean filterReadSet(final List<GATKRead> reads){
        // watch out for "if (condition) return true"
        if (reads.size() < MINIMUM_REQUIRED_SET_SIZE){
            return true;
        }

        // More to come...
        return false;
    }

    /** Having identified the best haplotypes, give quals to each position of the consensus read **/
    public void updateBaseQuals(final GATKRead consensusRead, final List<GATKRead> reads, final Set<String> samplesList){
        final Cigar consensusReadCigar = consensusRead.getCigar(); // Mostly for debugging
        final int readLength = consensusRead.getLength();
        final LocusIteratorByState libs = new LocusIteratorByState(reads.iterator(), DownsamplingMethod.NONE, false, samplesList,
                header, true);

        // Use SamLocusIterator?
        int currentConsensusPositionInReference = consensusRead.getStart(); // This is the position in the reference

        AlignmentContext alignmentContext = libs.next();
        // Line up the start of the iterator with the start of the consensus read
        while (alignmentContext.getStart() != consensusRead.getStart()){
            alignmentContext = libs.next();
        }

        final byte[] newBaseQualities = new byte[readLength];
        final byte[] newBases = new byte[readLength];
        // Alignment state machine gives us the current Cigar element, which is useful but may not be necessary.
        final AlignmentStateMachine asm = new AlignmentStateMachine(consensusRead);
        asm.stepForwardOnGenome(); // Initialize

        // Map of the index of the start of each cigar element
        final Pair<List<Integer>, List<Integer>> insertionStartIndicesAndLengths = getInsertionStartIndicesAndLengths(consensusReadCigar);
        final boolean consensusContainsInsertion = ! insertionStartIndicesAndLengths.getLeft().isEmpty();

        for (int i = 0; i < readLength; i++){
            Utils.validate(asm.getLocation().getStart() == alignmentContext.getStart(), "read and locus iterator out of sync");
            if (consensusContainsInsertion && insertionStartIndicesAndLengths.getLeft().contains(i)){ // alignment context 78
                // For now, do not update the qualities of inserted bases
                final int insertionIndex = insertionStartIndicesAndLengths.getLeft().indexOf(i);
                final int insertionLength = insertionStartIndicesAndLengths.getRight().get(insertionIndex);
                for (int j = 0; j < insertionLength; j++){
                    final byte CLEARLY_FALSE_DEFAULT_INSERTED_BASE_QUALITY = 20;
                    final byte CLEARLY_FALSE_DEFAULT_INSERTED_BASE = BaseUtils.baseIndexToSimpleBase(0);
                    newBases[i+j] = CLEARLY_FALSE_DEFAULT_INSERTED_BASE;
                    newBaseQualities[i+j] = CLEARLY_FALSE_DEFAULT_INSERTED_BASE_QUALITY;
                }
                i += insertionLength - 1; // minus 1 because the for loop increments i too
                continue;
            }

            if (alignmentContext.getStart() != currentConsensusPositionInReference){
                logger.warn("AlignmentContext and read position in reference are out of sync: " + alignmentContext.getStart() + ", " + currentConsensusPositionInReference);
            }

            final CigarElement currentCigarElementInConsensus = asm.getCurrentCigarElement();
            if (currentCigarElementInConsensus == null){
                throw new UserException("Read = " + consensusRead.getName() + ", position = " + consensusRead.getStart() + ", cigar = " + consensusRead.getCigar() + ", asm position = " + asm.getLocation());
            }

            if (currentCigarElementInConsensus.getOperator() == CigarOperator.I){
                // TODO: I suspect this never happens i.e. this should throw an exception
                // Don't want to step forward on genome.
                throw new UserException("As I understand the code, this should not happen");
            }
            // In the event of a D cigar, skip to the first base after the deletion. We don't increment i here
            // TODO: Write test
            if (currentCigarElementInConsensus.getOperator() == CigarOperator.D){ // Something like this...
                final int deletionLength = currentCigarElementInConsensus.getLength();
                // When deletionLength = 1, we want advanceToLocus() to be a no-op, since the destination (argument) equals the position of the current
                // AlignmentContext. But advanceToLocus() advances the iterator
                // before checking if the destination equals the current position, and this thorws off the calculation.
                if (deletionLength > 1){
                    libs.advanceToLocus(alignmentContext.getStart() + deletionLength - 1, true); // is true OK here?
                }
                alignmentContext = libs.next();
                currentConsensusPositionInReference = alignmentContext.getStart();
                IntStream.range(0, deletionLength).forEach(z -> asm.stepForwardOnGenome());
            }

            // Having processed the cigar, the read offset and asm must be in sync at this point
            Utils.validate(i == asm.getReadOffset(), "is this true? i = " + i + ", asm  = " + asm.getReadOffset());

            final ReadPileup pileup = alignmentContext.getBasePileup();
            final int[] baseCounts = pileup.getBaseCounts();
            final boolean disagreement = Arrays.stream(baseCounts).filter(x -> x > 0).count() > 1;
            if (disagreement){
                int curiousToSee = 3;
            }
            final List<PileupElement> pes = pileup.getPileupElements();
            final long numDeletions = pes.stream().filter(PileupElement::isDeletion).count();
            final long numInsertionStart = pes.stream().filter(PileupElement::isBeforeInsertion).count();
            if (numInsertionStart > 0){
                int d = 3;
            }

            // We should skip computation if there's no collision e.g. base count is [0 0 15 0]
            final Pair<Integer, Double> result = getBaseQualityFromDependentEvidence(pileup.getBases(), pileup.getBaseQuals());
            newBases[i] = BaseUtils.baseIndexToSimpleBase(result.getLeft());
            newBaseQualities[i] = (byte) (-10 * result.getRight());

            if (libs.hasNext()){
                alignmentContext = libs.next();
            } else {
                Utils.validate(i == readLength - 1, "Bug: LocusIterator ran out of bases. i = " + i + ", Cigar = " + consensusRead.getCigar().toString());
            }

            int d = 3; // ac.getStart() == 7_578_710
            currentConsensusPositionInReference++; // Unless it's an insertion base...not gonna think about it for now
            if (asm.isRightEdge() && i != readLength - 1) {
                throw new UserException("asm is at the right edge but we're index i = " + i + " isn't");
            }

            if (!asm.isRightEdge()){
                asm.stepForwardOnGenome();
            }
        }

        consensusRead.setBases(newBases);
        consensusRead.setBaseQualities(newBaseQualities);
    }

    /** Returns a list of (index, length) pairs of indel positions **/
    public static Pair<List<Integer>, List<Integer>> getInsertionStartIndicesAndLengths(final Cigar cigar) {
        final List<Integer> indices = new ArrayList<>();
        final List<Integer> lengths = new ArrayList<>();
        int currentIndex = 0;
        for (CigarElement ce : cigar.getCigarElements()){
            if (ce.getOperator() == CigarOperator.I){
                indices.add(currentIndex);
                lengths.add(ce.getLength());
            }

            currentIndex += ce.getLength();
        }
        return new ImmutablePair<>(indices, lengths);
    }

    // Do I need this?
    private Cigar getConsensusCigar(final List<GATKRead> reads, final TreeMap<LocationAndAlleles, Double> indelEvents) {
        final boolean reverseStrand = reads.get(0).isReverseStrand();
        if (reverseStrand){
            // build cigar from the largest coordinate, where they agree
            final List<Integer> endPositions = reads.stream().map(r -> r.getEnd()).distinct().collect(Collectors.toList());
            Utils.validate(endPositions.size() == 1, "The end positions of reverse reads in a duplicate set must agree");
            final int endPosition = endPositions.get(0);
            final LinkedList<CigarElement> cigarElements = new LinkedList<>();
            final TreeMap<LocationAndAlleles, Double> indelEventsInReverse = new TreeMap<>(Collections.reverseOrder());
            indelEventsInReverse.putAll(indelEvents);

            final LocationAndAlleles firstIndelLoc = indelEventsInReverse.pollFirstEntry().getKey(); // This is not quite right.
            // Utils.validate(endPosition > firstIndelLoc, "end position must be greater");
            // new CigarElement(endPosition - )
            // Is this worth it? Maybe just pick a representative read that contains these events.
            // could also use: CigarUtils.invertCigar
        }

        return new Cigar();
    }

    // TODO: add unit test. Also where does this belong?
    public Set<Haplotype> readsToHaplotypeSet(final List<GATKRead> reads, final SimpleInterval paddedRefInterval){
        final Set<Haplotype> haplotypes = new TreeSet<>();
        for (final GATKRead read : reads){
            final Haplotype hap = new Haplotype(read.getBases(), new SimpleInterval(read.getContig(), read.getStart(), read.getEnd()));
            hap.setCigar(read.getCigar());
            hap.setAlignmentStartHapwrtRef(read.getStart() - paddedRefInterval.getStart());
            haplotypes.add(hap);
        }
        return haplotypes;
    }

    // TODO: add unit test

    /**
     * Create the corresponding haplotype objects for each input GATK Read.
     * The only tricky bit is the setAlignmentStartHapwrtRef.
     */
    public List<ReadAndHaplotype> getReadAndHaplotypes(final List<GATKRead> reads, final SimpleInterval paddedRefInterval){
        final List<ReadAndHaplotype> readAndHaplotypes = new ArrayList<>();
        for (final GATKRead read : reads){
            final Haplotype hap = new Haplotype(read.getBases(), new SimpleInterval(read.getContig(), read.getStart(), read.getEnd()));
            hap.setCigar(read.getCigar());
            // TODO: What is this? Can I use getReferenceCoordinateForReadCoordinate?
            hap.setAlignmentStartHapwrtRef(read.getStart() - paddedRefInterval.getStart());
            readAndHaplotypes.add(new ReadAndHaplotype(read, hap));
        }
        return readAndHaplotypes;
    }

    /**
     * @return The index of the consensus base and the associated error probability
     */
    public Pair<Integer, Double> getBaseQualityFromDependentEvidence(final byte[] basesAtLocus, final byte[] quals){
        Utils.validateArg(basesAtLocus.length == quals.length, "");

        final double[] log10Likelihoods = new double[NUM_POSSIBLE_BASES];

        for (int b = 0; b < NUM_POSSIBLE_BASES; b++){
            for (int i = 0; i < basesAtLocus.length; i++){
                if (BaseUtils.simpleBaseToBaseIndex(basesAtLocus[i]) == b){ // how does bases work....
                    log10Likelihoods[b] += QualityUtils.qualToProbLog10(quals[i]);
                } else {
                    log10Likelihoods[b] += - MathUtils.log10(3) + QualityUtils.qualToErrorProbLog10(quals[i]);
                }
            }
        }

        /** Question: are there advantages in using log for values close to 1? **/
        final double logNormalization = MathUtils.log10sumLog10(log10Likelihoods);
        final double[] log10NormalizedPosterior = Arrays.stream(log10Likelihoods).map(l -> l - logNormalization).toArray();
        final int indexOfConsensusBase = MathUtils.maxElementIndex(log10NormalizedPosterior);
        final double log10PosteriorIndependentErrorProb = IntStream.range(0, NUM_POSSIBLE_BASES).filter(i -> i != indexOfConsensusBase)
                .mapToDouble(i -> log10NormalizedPosterior[i]).sum();
        final double log10PcrError = getLog10PCRErrorProb();

        return new ImmutablePair<>(indexOfConsensusBase, MathUtils.log10sumLog10(new double[]{ log10PosteriorIndependentErrorProb, log10PcrError }));
    }

    private double getLog10PCRErrorProb(){
        // This is where I use the Weiss-model
        return -5.0;
    }

    private static StrandAndReadNum getReadStrandReadNum(final GATKRead read){
        if (ReadUtils.isF1R2(read)){ // TOP strand
            return read.isFirstOfPair() ? StrandAndReadNum.TOP_READ_ONE : StrandAndReadNum.TOP_READ_TWO;
        } else { // BOTTOM strand
            return read.isFirstOfPair() ? StrandAndReadNum.BOTTOM_READ_ONE : StrandAndReadNum.BOTTOM_READ_TWO;
        }
    }

    // TOP R1       TOP R2
    // ----->       <------
    // ----->       <------
    // BOTTOM R2    BOTTOM R1
    enum StrandAndReadNum {
        TOP_READ_ONE,
        BOTTOM_READ_TWO,
        TOP_READ_TWO,
        BOTTOM_READ_ONE
    }
}
