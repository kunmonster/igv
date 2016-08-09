/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2007-2015 Broad Institute
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package org.broad.igv.sam;

import org.apache.log4j.Logger;
import org.broad.igv.feature.Range;
import org.broad.igv.feature.Strand;

import java.util.*;

/**
 * Packs alignments such that there is no overlap
 *
 * @author jrobinso
 */
public class AlignmentPacker {

    private static final Logger log = Logger.getLogger(AlignmentPacker.class);

    /**
     * Minimum gap between the end of one alignment and start of another.
     */
    public static final int MIN_ALIGNMENT_SPACING = 5;
    private static final Comparator<Alignment> lengthComparator = new Comparator<Alignment>() {
        public int compare(Alignment row1, Alignment row2) {
            return (row2.getEnd() - row2.getStart()) -
                    (row1.getEnd() - row2.getStart());

        }
    };

    private static final String NULL_GROUP_VALUE = "";
    public static final int tenMB = 10000000;

    /**
     * Allocates each alignment to row such that there is no overlap.
     */
    public PackedAlignments packAlignments(
            AlignmentInterval interval,
            AlignmentTrack.RenderOptions renderOptions) {

        if (renderOptions == null) renderOptions = new AlignmentTrack.RenderOptions();

        LinkedHashMap<String, List<Row>> packedAlignments = new LinkedHashMap<String, List<Row>>();


        List<Alignment> alList = interval.getAlignments();
        // TODO -- means to undo this
        if (renderOptions.isLinkedReads()) {
            alList = linkByTag(alList, renderOptions.getLinkByTag());
        }

        if (renderOptions.groupByOption == null) {
            List<Row> alignmentRows = new ArrayList<Row>(10000);
            pack(alList, renderOptions, alignmentRows);
            packedAlignments.put("", alignmentRows);
        } else {

            // Separate alignments into groups.
            Map<String, List<Alignment>> groupedAlignments = new HashMap<String, List<Alignment>>();
            Iterator<Alignment> iter = alList.iterator();
            while (iter.hasNext()) {
                Alignment alignment = iter.next();
                String groupKey = getGroupValue(alignment, renderOptions);
                if (groupKey == null) {
                    groupKey = NULL_GROUP_VALUE;
                }
                List<Alignment> groupList = groupedAlignments.get(groupKey);
                if (groupList == null) {
                    groupList = new ArrayList<Alignment>(1000);
                    groupedAlignments.put(groupKey, groupList);
                }
                groupList.add(alignment);
            }


            // Now alphabetize (sort) and pack the groups
            List<String> keys = new ArrayList<String>(groupedAlignments.keySet());
            Comparator<String> groupComparator = getGroupComparator(renderOptions.groupByOption);
            Collections.sort(keys, groupComparator);

            for (String key : keys) {
                List<Row> alignmentRows = new ArrayList<Row>(10000);
                List<Alignment> group = groupedAlignments.get(key);
                pack(group, renderOptions, alignmentRows);

                if (renderOptions.isLinkedReads()) {
                    for (Row row : alignmentRows) {
                        row.updateScore(AlignmentTrack.SortOption.MAX_GAP, 0, interval, "");
                    }
                    alignmentRows.sort(new Comparator<Row>() {
                        @Override
                        public int compare(Row o1, Row o2) {
                            return o1.compareTo(o2);
                        }
                    });
                }

                packedAlignments.put(key, alignmentRows);
            }
        }

        List<AlignmentInterval> tmp = new ArrayList<AlignmentInterval>();
        tmp.add(interval);
        return new PackedAlignments(tmp, packedAlignments);
    }


    private void pack(List<Alignment> alList, AlignmentTrack.RenderOptions renderOptions, List<Row> alignmentRows) {

        Map<String, PairedAlignment> pairs = null;

        boolean isPairedAlignments = renderOptions.isViewPairs() || renderOptions.isPairedArcView();
        String colorByTag = renderOptions.getColorByTag();
        String linkByTag = renderOptions.getLinkByTag();
        boolean isLinkedReads = renderOptions.isLinkedReads() && linkByTag != null;

        if (isPairedAlignments) {
            pairs = new HashMap<String, PairedAlignment>(1000);
        }


        // Allocate alignemnts to buckets for each range.
        // We use priority queues to keep the buckets sorted by alignment length.  However this  is probably a needless
        // complication,  any collection type would do.

        int totalCount = 0;


        if (alList == null || alList.size() == 0) return;

        Range curRange = getAlignmentListRange(alList);

        BucketCollection bucketCollection;

        // Use dense buckets for < 10,000,000 bp windows sparse otherwise
        int bpLength = curRange.getLength();

        if (bpLength < tenMB) {
            bucketCollection = new DenseBucketCollection(bpLength, curRange);
        } else {
            bucketCollection = new SparseBucketCollection(curRange);
        }

        int curRangeStart = curRange.getStart();
        for (Alignment al : alList) {

            if (al.isMapped()) {
                Alignment alignment = al;
                if (isPairedAlignments && al.isPaired() && al.getMate().isMapped() && al.getMate().getChr().equals(al.getChr())) {
                    String readName = al.getReadName();
                    PairedAlignment pair = pairs.get(readName);
                    if (pair == null) {
                        pair = new PairedAlignment(al);
                        pairs.put(readName, pair);
                        alignment = pair;
                    } else {
                        // Add second alignment to pair.
                        pair.setSecondAlignment(al);
                        pairs.remove(readName);
                        continue;

                    }
                }
                // Negative "bucketNumbers" can arise with soft clips at the left edge of the chromosome. Allocate
                // these alignments to the first bucket.
                int bucketNumber = Math.max(0, al.getStart() - curRangeStart);
                if (bucketNumber < bucketCollection.getBucketCount()) {
                    PriorityQueue<Alignment> bucket = bucketCollection.get(bucketNumber);
                    if (bucket == null) {
                        bucket = new PriorityQueue<Alignment>(5, lengthComparator);
                        bucketCollection.set(bucketNumber, bucket);
                    }
                    bucket.add(alignment);
                    totalCount++;
                } else {
                    log.debug("Alignment out of bounds. name: " + alignment.getReadName() + " startPos:" + alignment.getStart());
                }
            }
        }
        bucketCollection.finishedAdding();


        // Now allocate alignments to rows.
        long t0 = System.currentTimeMillis();
        int allocatedCount = 0;
        Row currentRow = new Row();

        while (allocatedCount < totalCount) {


            curRange = bucketCollection.getRange();

            curRangeStart = curRange.getStart();
            int nextStart = curRangeStart;
            List<Integer> emptyBuckets = new ArrayList<Integer>(100);

            while (true) {
                int bucketNumber = nextStart - curRangeStart;
                PriorityQueue<Alignment> bucket = bucketCollection.getNextBucket(bucketNumber, emptyBuckets);

                // Pull the next alignment out of the bucket and add to the current row
                if (bucket != null) {
                    Alignment alignment = bucket.remove();
                    currentRow.addAlignment(alignment);
                    allocatedCount++;

                    nextStart = alignment.getEnd() + MIN_ALIGNMENT_SPACING;

                }

                //Reached the end of this range, move to the next
                if (bucket == null || nextStart > curRange.getEnd()) {
                    //Remove empty buckets.  This has no affect on the dense implementation,
                    //they are removed on the fly, but is needed for the sparse implementation
                    bucketCollection.removeBuckets(emptyBuckets);
                    emptyBuckets.clear();
                    break;
                }
            }


            // We've reached the end of the interval,  start a new row
            if (currentRow.alignments.size() > 0) {
                alignmentRows.add(currentRow);
            }
            currentRow = new Row();
        }
        if (log.isDebugEnabled()) {
            long dt = System.currentTimeMillis() - t0;
            log.debug("Packed alignments in " + dt);
        }

        // Add the last row
        if (currentRow != null && currentRow.alignments.size() > 0) {
            alignmentRows.add(currentRow);
        }


    }

    private List<Alignment> linkByTag(List<Alignment> alList, String tag) {

        List<Alignment> bcList = new ArrayList<>(alList.size() / 10);
        Map<Object, LinkedAlignment> map = new HashMap<>(bcList.size() * 2);

        for (Alignment a : alList) {

            if(a.isPrimary()) {
                Object bc = ("READNAME".equals(tag)) ? a.getReadName() :  a.getAttribute(tag);

                if (bc == null) {
                    bcList.add(a);
                } else {
                    LinkedAlignment linkedAlignment = map.get(bc);
                    if (linkedAlignment == null) {
                        linkedAlignment = new LinkedAlignment(tag, bc.toString());
                        map.put(bc, linkedAlignment);
                        bcList.add(linkedAlignment);
                    }
                    linkedAlignment.addAlignment(a);
                }
            }
            else {
                // Don't link secondary reads
                bcList.add(a);
            }
        }

        // Now copy list, de-linking orhpaned alignments (alignments with no linked mates)
        List<Alignment> delinkedList = new ArrayList<>(alList.size());
        for(Alignment a : bcList) {
            if(a instanceof LinkedAlignment) {
                final List<Alignment> alignments = ((LinkedAlignment) a).alignments;
                if(alignments.size() == 1) {
                    delinkedList.add(alignments.get(0));
                }
                else {
                    delinkedList.add(a);
                }
            }
            else {
                delinkedList.add(a);
            }
        }

        return bcList;
    }


    /**
     * Gets the range over which alignmentsList spans. Asssumes all on same chr, and sorted
     *
     * @param alignmentsList
     * @return
     */
    private Range getAlignmentListRange(List<Alignment> alignmentsList) {
        if (alignmentsList == null || alignmentsList.size() == 0) return null;
        Alignment firstAlignment = alignmentsList.get(0);

        int minStart = firstAlignment.getStart();
        int maxEnd = firstAlignment.getEnd();
        for (Alignment alignment : alignmentsList) {
            maxEnd = Math.max(maxEnd, alignment.getEnd());
        }
        return new Range(firstAlignment.getChr(), minStart,
                maxEnd);
    }

    private Comparator<String> getGroupComparator(AlignmentTrack.GroupOption groupByOption) {
        switch (groupByOption) {
            case PAIR_ORIENTATION:
                return new PairOrientationComparator();
            default:
                //Sort null values towards the end
                return new Comparator<String>() {
                    @Override
                    public int compare(String o1, String o2) {
                        if (o1 == null && o2 == null) {
                            return 0;
                        } else if (o1 == null) {
                            return 1;
                        } else if (o2 == null) {
                            return -1;
                        } else {
                            // no nulls
                            if (o1.equals(o2)) {
                                return 0;
                            } else if (NULL_GROUP_VALUE.equals(o1)) {
                                return 1;
                            }
                            if (NULL_GROUP_VALUE.equals(o2)) {
                                return -1;
                            } else {
                                return o1.compareToIgnoreCase(o2);
                            }
                        }
                    }
                };
        }
    }

    private String getGroupValue(Alignment al, AlignmentTrack.RenderOptions renderOptions) {

        AlignmentTrack.GroupOption groupBy = renderOptions.groupByOption;
        String tag = renderOptions.getGroupByTag();
        Range pos = renderOptions.getGroupByBaseAtPos();

        switch (groupBy) {
            case STRAND:
                return String.valueOf(al.isNegativeStrand());
            case SAMPLE:
                return al.getSample();
            case READ_GROUP:
                return al.getReadGroup();
            case TAG:
                Object tagValue = al.getAttribute(tag);
                return tagValue == null ? null : tagValue.toString();
            case FIRST_OF_PAIR_STRAND:
                Strand strand = al.getFirstOfPairStrand();
                String strandString = strand == Strand.NONE ? null : strand.toString();
                return strandString;
            case PAIR_ORIENTATION:
                PEStats peStats = AlignmentRenderer.getPEStats(al, renderOptions);
                AlignmentTrack.OrientationType type = AlignmentRenderer.getOrientationType(al, peStats);
                if (type == null) {
                    return AlignmentTrack.OrientationType.UNKNOWN.name();
                }
                return type.name();
            case MATE_CHROMOSOME:
                ReadMate mate = al.getMate();
                if (mate == null) {
                    return null;
                }
                if (mate.isMapped() == false) {
                    return "UNMAPPED";
                } else {
                    return mate.getChr();
                }
            case SUPPLEMENTARY:
                return String.valueOf(!al.isSupplementary());
            case BASE_AT_POS:
                // Use a string prefix to enforce grouping rules:
                //    1: alignments with a base at the position
                //    2: alignments with a gap at the position
                //    3: alignment that do not overlap the position (or are on a different chromosome)

                if (al.getChr().equals(pos.getChr()) &&
                    al.getAlignmentStart() <= pos.getStart() &&
                    al.getAlignmentEnd() > pos.getStart()) {

                    byte[] baseAtPos = new byte[] {al.getBase(pos.getStart())};
                    if (baseAtPos[0] == 0) { // gap at position
                        return "2:";
                    }
                    else { // base at position
                        return "1:" + new String(baseAtPos);
                    }
                }
                else { // does not overlap position
                    return "3:";
                }
        }
        return null;
    }

    interface BucketCollection {

        Range getRange();

        void set(int idx, PriorityQueue<Alignment> bucket);

        PriorityQueue<Alignment> get(int idx);

        PriorityQueue<Alignment> getNextBucket(int bucketNumber, Collection<Integer> emptyBuckets);

        void removeBuckets(Collection<Integer> emptyBuckets);

        void finishedAdding();

        int getBucketCount();
    }

    /**
     * Dense array implementation of BucketCollection.  Assumption is all or nearly all the genome region is covered
     * with reads.
     */
    static class DenseBucketCollection implements BucketCollection {

        Range range;
        int lastBucketNumber = -1;
        final PriorityQueue[] bucketArray;

        DenseBucketCollection(int bucketCount, Range range) {
            this.bucketArray = new PriorityQueue[bucketCount];
            this.range = range;
        }

        public void set(int idx, PriorityQueue<Alignment> bucket) {
            bucketArray[idx] = bucket;
        }

        public PriorityQueue<Alignment> get(int idx) {
            return bucketArray[idx];
        }

        public int getBucketCount() {
            return this.bucketArray.length;
        }

        public Range getRange() {
            return range;
        }

        /**
         * Return the next occupied bucket after bucketNumber
         *
         * @param bucketNumber
         * @param emptyBuckets ignored
         * @return
         */
        public PriorityQueue<Alignment> getNextBucket(int bucketNumber, Collection<Integer> emptyBuckets) {

            if (bucketNumber == lastBucketNumber) {
                // TODO -- detect inf loop here
            }

            PriorityQueue<Alignment> bucket = null;
            while (bucketNumber < bucketArray.length) {

                if (bucketNumber < 0) {
                    log.info("Negative bucket number: " + bucketNumber);
                }

                bucket = bucketArray[bucketNumber];
                if (bucket != null) {
                    if (bucket.isEmpty()) {
                        bucketArray[bucketNumber] = null;
                    } else {
                        return bucket;
                    }
                }
                bucketNumber++;
            }
            return null;
        }

        public void removeBuckets(Collection<Integer> emptyBuckets) {
            // Nothing to do, empty buckets are removed "on the fly"
        }

        public void finishedAdding() {
            // nothing to do
        }
    }


    /**
     * "Sparse" implementation of an alignment BucketCollection.  Assumption is there are small clusters of alignments
     * along the genome, with mostly "white space".
     */
    static class SparseBucketCollection implements BucketCollection {

        Range range;
        boolean finished = false;
        List<Integer> keys;
        final HashMap<Integer, PriorityQueue<Alignment>> buckets;

        SparseBucketCollection(Range range) {
            this.range = range;
            this.buckets = new HashMap(1000);
        }

        public void set(int idx, PriorityQueue<Alignment> bucket) {
            if (finished) {
                log.error("Error: bucket added after finishAdding() called");
            }
            buckets.put(idx, bucket);
        }

        public PriorityQueue<Alignment> get(int idx) {
            return buckets.get(idx);
        }

        public Range getRange() {
            return range;
        }

        /**
         * Return the next occupied bucket at or after after bucketNumber.
         *
         * @param bucketNumber -- the hash bucket index for the alignments, essential the position relative to the start
         *                     of this packing interval
         * @return the next occupied bucket at or after bucketNumber, or null if there are none.
         */
        public PriorityQueue<Alignment> getNextBucket(int bucketNumber, Collection<Integer> emptyBuckets) {

            PriorityQueue<Alignment> bucket = null;
            int min = 0;
            int max = keys.size() - 1;

            // Get close to the right index, rather than scan from the beginning
            while ((max - min) > 5) {
                int mid = (max + min) / 2;
                Integer key = keys.get(mid);
                if (key > bucketNumber) {
                    max = mid;
                } else {
                    min = mid;
                }
            }

            // Now march from min to max until we cross bucketNumber
            for (int i = min; i < keys.size(); i++) {
                Integer key = keys.get(i);
                if (key >= bucketNumber) {
                    bucket = buckets.get(key);
                    if (bucket.isEmpty()) {
                        emptyBuckets.add(key);
                        bucket = null;
                    } else {
                        return bucket;
                    }
                }
            }
            return null;     // No bucket found
        }

        public void removeBuckets(Collection<Integer> emptyBuckets) {

            if (emptyBuckets.isEmpty()) {
                return;
            }

            for (Integer i : emptyBuckets) {
                buckets.remove(i);
            }
            keys = new ArrayList<Integer>(buckets.keySet());
            Collections.sort(keys);
        }

        public void finishedAdding() {
            finished = true;
            keys = new ArrayList<Integer>(buckets.keySet());
            Collections.sort(keys);
        }

        public int getBucketCount() {
            return Integer.MAX_VALUE;
        }
    }

    private class PairOrientationComparator implements Comparator<String> {
        private final List<AlignmentTrack.OrientationType> orientationTypes;
        //private final Set<String> orientationNames = new HashSet<String>(AlignmentTrack.OrientationType.values().length);

        public PairOrientationComparator() {
            orientationTypes = Arrays.asList(AlignmentTrack.OrientationType.values());
//            for(AlignmentTrack.OrientationType type: orientationTypes){
//                orientationNames.add(type.name());
//            }
        }

        @Override
        public int compare(String s0, String s1) {
            if (s0 != null && s1 != null) {
                AlignmentTrack.OrientationType t0 = AlignmentTrack.OrientationType.valueOf(s0);
                AlignmentTrack.OrientationType t1 = AlignmentTrack.OrientationType.valueOf(s1);
                return orientationTypes.indexOf(t0) - orientationTypes.indexOf(t1);
            } else if (s0 == null ^ s1 == null) {
                //exactly one is null
                return s0 == null ? 1 : -1;
            } else {
                //both null
                return 0;
            }

        }
    }

}
