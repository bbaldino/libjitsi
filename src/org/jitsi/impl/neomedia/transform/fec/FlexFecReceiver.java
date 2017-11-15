package org.jitsi.impl.neomedia.transform.fec;

import org.jitsi.impl.neomedia.transform.*;
import org.jitsi.service.configuration.*;
import org.jitsi.service.libjitsi.*;
import org.jitsi.service.neomedia.*;
import org.jitsi.util.*;

import java.util.*;

/**
 * Created by bbaldino on 11/9/17.
 */
//FIXME: anywhere we use getBuffer needs to take into account getOffset
public class FlexFecReceiver
    implements PacketTransformer
{
    class Statistics {
        int numFecPacketsReceived = 0;
        int numPacketsRecovered = 0;
    }
    /**
     * The <tt>Logger</tt> used by the <tt>FlexFecReceiver</tt> class and
     * its instances to print debug information.
     */
    private static final Logger logger
        = Logger.getLogger(FlexFecReceiver.class);

    /**
     * The number of media packets to keep.
     */
    private static final int MEDIA_BUF_SIZE;

    /**
     * The maximum number of ulpfec packets to keep.
     */
    private static final int FEC_BUF_SIZE;

    /**
     * The name of the <tt>ConfigurationService</tt> property which specifies
     * the value of {@link #MEDIA_BUF_SIZE}.
     */
    private static final String MEDIA_BUF_SIZE_PNAME
        = FECReceiver.class.getName() + ".MEDIA_BUFF_SIZE";

    /**
     * The name of the <tt>ConfigurationService</tt> property which specifies
     * the value of {@link #FEC_BUF_SIZE}.
     */
    private static final String FEC_BUF_SIZE_PNAME
        = FECReceiver.class.getName() + ".FEC_BUFF_SIZE";

    static
    {
        ConfigurationService cfg = LibJitsi.getConfigurationService();
        int fecBufSize = 32;
        int mediaBufSize = 64;

        if (cfg != null)
        {
            fecBufSize = cfg.getInt(FEC_BUF_SIZE_PNAME, fecBufSize);
            mediaBufSize = cfg.getInt(MEDIA_BUF_SIZE_PNAME, mediaBufSize);
        }
        FEC_BUF_SIZE = fecBufSize;
        MEDIA_BUF_SIZE = mediaBufSize;
    }

    /**
     * The ssrc of the media stream the fec stream is protecting
     */
    private long mediaSsrc;

    /**
     * The ssrc of the fec stream
     */
    private long fecSsrc;

    /**
     * FEC-related statistics
     */
    private Statistics statistics;

    /**
     *
     */
    private Reconstructor reconstructor;

    /**
     * Buffer which keeps (copies of) received media packets.
     *
     * We keep them ordered by their RTP sequence numbers, so that
     * we can easily select the oldest one to discard when the buffer is
     * full (when the map has more than <tt>MEDIA_BUFF_SIZE</tt> entries).
     *
     * We keep them in a <tt>Map</tt> so that we can easily search for a
     * packet with a specific sequence number.
     *
     * Note: This might turn out to be inefficient, especially with increased
     * buffer sizes. In the vast majority of cases (e.g. on every received
     * packet) we do an insert at one end and a delete from the other -- this
     * can be optimized. We very rarely (when we receive a packet out of order)
     * need to insert at an arbitrary location.
     */
    private final SortedMap<Integer, RawPacket> mediaPackets
        = new TreeMap<>(FecUtils.seqNumComparator);

    /**
     * Buffer which keeps references to received fec packets.
     *
     * We keep them ordered by their RTP sequence numbers, so that
     * we can easily select the oldest one to discard when the buffer is
     * full (when the map has more than <tt>FEC_BUFF_SIZE</tt> entries.
     *
     * We keep them in a <tt>Map</tt> so that we can easily search for a
     * packet with a specific sequence number.
     *
     * Note: This might turn out to be inefficient, especially with increased
     * buffer sizes. In the vast majority of cases (e.g. on every received
     * packet) we do an insert at one end and a delete from the other -- this
     * can be optimized. We very rarely (when we receive a packet out of order)
     * need to insert at an arbitrary location.
     */
    private final SortedMap<Integer, FlexFecPacket> fecPackets
        = new TreeMap<>(FecUtils.seqNumComparator);


    public FlexFecReceiver(long mediaSsrc, long fecSsrc)
    {
        this.mediaSsrc = mediaSsrc;
        this.fecSsrc = fecSsrc;
        this.statistics = new Statistics();
        this.reconstructor = new Reconstructor(mediaPackets);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close()
    {
        if (logger.isInfoEnabled())
        {
            logger.info("Closing FlexFecReceiver stream " + this.fecSsrc
                + " (protecting " + this.mediaSsrc + ")."
                + "Recovered " + this.statistics.numPacketsRecovered +
                " media packets");
        }
    }

    /**
     * Saves <tt>p</tt> into <tt>fecPackets</tt>. If the size of
     * <tt>fecPackets</tt> has reached <tt>FEC_BUFF_SIZE</tt> discards the
     * oldest packet from it.
     * @param p the packet to save.
     */
    private void saveFec(RawPacket p)
    {
        FlexFecPacket flexFecPacket = new FlexFecPacket(p);
        if (fecPackets.size() >= FEC_BUF_SIZE)
            fecPackets.remove(fecPackets.firstKey());

        fecPackets.put(p.getSequenceNumber(), flexFecPacket);
    }

    /**
     * Makes a copy of <tt>p</tt> into <tt>mediaPackets</tt>. If the size of
     * <tt>mediaPackets</tt> has reached <tt>MEDIA_BUFF_SIZE</tt> discards
     * the oldest packet from it and reuses it.
     * @param p the packet to copy.
     */
    private void saveMedia(RawPacket p)
    {
        RawPacket newMedia;
        if (mediaPackets.size() < MEDIA_BUF_SIZE)
        {
            newMedia = new RawPacket();
            newMedia.setBuffer(new byte[FECTransformEngine.INITIAL_BUFFER_SIZE]);
            newMedia.setOffset(0);
        }
        else
        {
            newMedia = mediaPackets.remove(mediaPackets.firstKey());
        }

        int pLen = p.getLength();
        if (pLen > newMedia.getBuffer().length)
        {
            newMedia.setBuffer(new byte[pLen]);
        }

        System.arraycopy(p.getBuffer(), p.getOffset(), newMedia.getBuffer(),
            0, pLen);
        newMedia.setLength(pLen);

        mediaPackets.put(newMedia.getSequenceNumber(), newMedia);
    }

    @Override
    public synchronized RawPacket[] reverseTransform(RawPacket[] pkts)
    {
        for (int i = 0; i < pkts.length; ++i)
        {
            RawPacket pkt = pkts[i];
            if (pkt.getSSRCAsLong() == this.fecSsrc)
            {
                // Don't forward it
                pkts[i] = null;
                statistics.numFecPacketsReceived++;
                saveFec(pkt);

            }
            else if (pkt.getSSRCAsLong() == this.mediaSsrc)
            {
                saveMedia(pkt);
            }
        }

        Set<Integer> flexFecPacketsToRemove = new HashSet<>();
        // Try to recover any missing media packets
        for (Map.Entry<Integer, FlexFecPacket> entry : fecPackets.entrySet())
        {
            FlexFecPacket flexFecPacket = entry.getValue();
            reconstructor.setFecPacket(flexFecPacket);
            if (reconstructor.complete())
            {
                flexFecPacketsToRemove.add(flexFecPacket.getSequenceNumber());
                continue;
            }
            if (reconstructor.canRecover())
            {
                flexFecPacketsToRemove.add(flexFecPacket.getSequenceNumber());
                RawPacket recovered = reconstructor.recover();
                if (recovered != null)
                {
                    statistics.numPacketsRecovered++;
                    saveMedia(recovered);
                    pkts = insert(recovered, pkts);
                }
            }
        }

        for (Integer flexFecSeqNum : flexFecPacketsToRemove)
        {
            fecPackets.remove(flexFecSeqNum);
        }
        return pkts;
    }

    /**
     * Inserts packet into an empty slot in pkts, or allocates a new
     * array and inserts packet into it.  Returns either the original
     * array (with packet insert) or a new array containing the original contents
     * of pkts and with packet inserted
     * @param packet the packet to be inserted
     * @param pkts the array in which to insert packet
     * @return the original pkts array with packet inserted, or, a new array
     * containing all elements in pkts as well as packet
     */
    private RawPacket[] insert(RawPacket packet, RawPacket[] pkts)
    {
        for (int i = 0; i < pkts.length; ++i)
        {
            if (pkts[i] == null)
            {
                pkts[i] = packet;
                return pkts;
            }
        }

        RawPacket[] newPkts = new RawPacket[pkts.length + 1];
        System.arraycopy(pkts, 0, newPkts, 0, pkts.length);
        newPkts[pkts.length] = packet;
        return newPkts;
    }

    /**
     * {@inheritDoc}
     * No-op
     */
    @Override
    public RawPacket[] transform(RawPacket[] pkts)
    {
        return pkts;
    }

    private static class Reconstructor
    {
        /**
         * All available media packets.
         */
        private Map<Integer, RawPacket> mediaPackets;

        /**
         * The ulpfec packet to be used for recovery.
         */
        private FlexFecPacket fecPacket = null;

        /**
         * We can only recover a single missing packet, so when we check
         * for how many are missing, we'll keep track of the first one we find
         */
        int missingSequenceNumber = -1;

        int numMissing = -1;

        /**
         * Initializes a new instance.
         * @param mediaPackets the currently available media packets
         */
        Reconstructor(Map<Integer, RawPacket> mediaPackets)
        {
            this.mediaPackets = mediaPackets;
        }

        public boolean complete()
        {
            return numMissing == 0;
        }

        public boolean canRecover()
        {
            return numMissing == 1;
        }

        public void setFecPacket(FlexFecPacket p)
        {
            this.fecPacket = p;
            if (p == null)
            {
                logger.error("Error creating flexfec packet");
                return;
            }

            for (Integer protectedSeqNum : fecPacket.getProtectedSequenceNumbers())
            {
                if (!mediaPackets.containsKey(protectedSeqNum))
                {
                    numMissing++;
                    missingSequenceNumber = protectedSeqNum;
                }
            }
            if (numMissing > 1)
            {
                missingSequenceNumber = -1;
            }
        }

        private boolean startPacketRecovery(FlexFecPacket fecPacket, RawPacket recoveredPacket)
        {
            // Copy over the recovery RTP header data from the fec packet
            System.arraycopy(fecPacket.getBuffer(), 0,
                recoveredPacket.getBuffer(), 0, 12);

            // Copy over the recovery rtp payload data from the fec packet
            System.arraycopy(fecPacket.getBuffer(), fecPacket.flexFecHeaderSizeBytes,
                recoveredPacket.getBuffer(), 12, fecPacket.getPayloadLength());

            return true;
        }

        private void xorHeaders(RawPacket source, RawPacket dest)
        {
            // XOR the first 2 bytes of the header: V, P, X, CC, M, PT fields.
            dest.getBuffer()[0] ^= source.getBuffer()[0];
            dest.getBuffer()[1] ^= source.getBuffer()[1];

            // XOR the length recovery field.
            int length = (source.getPayloadLength() & 0xffff);
            dest.getBuffer()[2] ^= (length >> 16);
            dest.getBuffer()[3] ^= (length & 0x00ff);

            // XOR the 5th to 8th bytes of the header: the timestamp field.
            dest.getBuffer()[4] ^= source.getBuffer()[4];
            dest.getBuffer()[5] ^= source.getBuffer()[5];
            dest.getBuffer()[6] ^= source.getBuffer()[6];
            dest.getBuffer()[7] ^= source.getBuffer()[7];

            // Skip the 9th to 12th bytes of the header.
        }

        private void xorPaylods(RawPacket source, int payloadLength,
                                RawPacket dest, int destOffset)
        {
            for (int i = 0; i < payloadLength; ++i)
            {
                dest.getBuffer()[destOffset + i] ^= source.getBuffer()[12 + i];
            }
        }

        private boolean finishPacketRecovery(FlexFecPacket fecPacket, RawPacket recoveredPacket)
        {
            // Set the RTP version to 2.
            recoveredPacket.getBuffer()[0] |= 0x80; // Set the 1st bit
            recoveredPacket.getBuffer()[0] &= 0xbf; // Clear the second bit

            // Recover the packet length, from temporary location.
            int length = RTPUtils.readUint16AsInt(recoveredPacket.getBuffer(), 2);
            recoveredPacket.setLength(length);

            // Set the SN field.
            RTPUtils.writeShort(recoveredPacket.getBuffer(), 2,
                (short)missingSequenceNumber);

            // Set the SSRC field.
            RTPUtils.writeInt(recoveredPacket.getBuffer(), 8,
                (int)fecPacket.protectedSsrc);

            return true;
        }

        private RawPacket recover()
        {
            if (!canRecover())
            {
                return null;
            }

            //TODO: use a pool?
            byte[] buf = new byte[1500];
            RawPacket recoveredPacket = new RawPacket(buf, 0, 1500);
            if (!startPacketRecovery(this.fecPacket, recoveredPacket))
            {
                return null;
            }
            recoveredPacket.setSequenceNumber(missingSequenceNumber);
            for (Integer protectedSeqNum : fecPacket.getProtectedSequenceNumbers())
            {
                if (protectedSeqNum != missingSequenceNumber)
                {
                    RawPacket mediaPacket = mediaPackets.get(protectedSeqNum);
                    xorHeaders(mediaPacket, recoveredPacket);
                    xorPaylods(mediaPacket, mediaPacket.getPayloadLength(),
                        recoveredPacket, 12);
                }
            }
            if (!finishPacketRecovery(fecPacket, recoveredPacket))
            {
                return null;
            }
            return recoveredPacket;
        }
    }

}
