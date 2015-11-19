/*
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jitsi.impl.neomedia.transform;

import java.util.*;
import org.jitsi.impl.neomedia.*;

/**
 * Does the dirty job of rewriting SSRCs and sequence numbers of a
 * given extended sequence number interval of a given source SSRC.
 *
 * @author George Politis
 */
class ExtendedSequenceNumberInterval
{
    /**
     * The extended minimum sequence number of this interval.
     */
    private final int extendedMinOrig;

    /**
     * Holds the value of the extended sequence number of the target
     * SSRC when this interval started.
     */
    private final int extendedBaseTarget;

    /**
     * The owner of this instance.
     */
    public final SsrcRewriter ssrcRewriter;

    /**
     * The extended maximum sequence number of this interval.
     */
    int extendedMaxOrig;

    /**
     * The time this interval has been closed.
     */
    long lastSeen;

    /**
     * Holds the max RTP timestamp that we've sent (to the endpoint)
     * in this interval.
     */
    long maxTimestamp;

    /**
     * Ctor.
     *
     * @param ssrcRewriter
     * @param extendedBaseOrig
     * @param extendedBaseTarget
     */
    public ExtendedSequenceNumberInterval(
            SsrcRewriter ssrcRewriter,
            int extendedBaseOrig,
            int extendedBaseTarget)
    {
        this.ssrcRewriter = ssrcRewriter;
        this.extendedBaseTarget = extendedBaseTarget;

        this.extendedMinOrig = extendedBaseOrig;
        this.extendedMaxOrig = extendedBaseOrig;
    }

    public long getLastSeen()
    {
        return lastSeen;
    }

    public int getExtendedMin()
    {
        return extendedMinOrig;
    }

    public int getExtendedMax()
    {
        return extendedMaxOrig;
    }

    /**
     * Returns a boolean determining whether a sequence number
     * is contained in this interval or not.
     *
     * @param extendedSequenceNumber the sequence number to
     * determine whether it belongs in the interval or not.
     * @return true if the sequence number is contained in the
     * interval, otherwise false.
     */
    public boolean contains(int extendedSequenceNumber)
    {
        return extendedMinOrig >= extendedSequenceNumber
            && extendedSequenceNumber <= extendedMaxOrig;
    }

    /**
     *
     * @param extendedSequenceNumber
     * @return
     */
    public int rewriteExtendedSequenceNumber(
        int extendedSequenceNumber)
    {
        int diff = extendedSequenceNumber - extendedMinOrig;
        return extendedBaseTarget + diff;
    }

    /**
     * @param pkt
     */
    public RawPacket rewriteRTP(RawPacket pkt)
    {
        // Rewrite the SSRC.
        int ssrcTarget = getSsrcGroupRewriter().getSSRCTarget();

        pkt.setSSRC(ssrcTarget);

        // Rewrite the sequence number of the RTP packet.
        short ssSeqnum = (short) pkt.getSequenceNumber();
        int extendedSequenceNumber
            = ssrcRewriter.extendOriginalSequenceNumber(ssSeqnum);
        int rewriteSeqnum = rewriteExtendedSequenceNumber(extendedSequenceNumber);
        // This will disregard the high 16 bits.
        pkt.setSequenceNumber(rewriteSeqnum);

        SsrcRewritingEngine ssrcRewritingEngine = getSsrcRewritingEngine();
        Map<Integer, Integer> rtx2primary = ssrcRewritingEngine.rtx2primary;
        int sourceSSRC = ssrcRewriter.getSourceSSRC();
        Integer primarySSRC = rtx2primary.get(sourceSSRC);
        if (primarySSRC == null)
        {
            primarySSRC = sourceSSRC;
        }

        boolean isRTX = rtx2primary.containsKey(sourceSSRC);

        // Take care of RED.
        Map<Integer, Byte> ssrc2red = ssrcRewritingEngine.ssrc2red;
        byte pt = pkt.getPayloadType();
        if (ssrc2red.get(sourceSSRC) == pt)
        {
            byte[] buf = pkt.getBuffer();
            int off = pkt.getPayloadOffset() + ((isRTX) ? 2 : 0);
            int len = pkt.getPayloadLength() - ((isRTX) ? 2 : 0);
            this.rewriteRED(primarySSRC, buf, off, len);
        }

        // Take care of FEC.
        Map<Integer, Byte> ssrc2fec = ssrcRewritingEngine.ssrc2fec;
        if (ssrc2fec.get(sourceSSRC) == pt)
        {
            byte[] buf = pkt.getBuffer();
            int off = pkt.getPayloadOffset() + ((isRTX) ? 2 : 0);
            int len = pkt.getPayloadLength() - ((isRTX) ? 2 : 0);
            // For the twisted case where we re-transmit a FEC
            // packet in an RTX packet..
            if (!this.rewriteFEC(primarySSRC, buf, off, len))
            {
                return null;
            }
        }

        // Take care of RTX and return the packet.
        return (!isRTX || this.rewriteRTX(pkt)) ? pkt : null;
    }

    /**
     *
     * @param pkt
     * @return
     */
    public boolean rewriteRTX(RawPacket pkt)
    {
        // This is an RTX packet. Replace RTX OSN field or drop.
        SsrcRewritingEngine ssrcRewritingEngine = getSsrcRewritingEngine();
        int sourceSSRC = ssrcRewriter.getSourceSSRC();
        int ssrcOrig = ssrcRewritingEngine.rtx2primary.get(sourceSSRC);
        short snOrig = pkt.getOriginalSequenceNumber();

        SsrcGroupRewriter rewriterPrimary
            = ssrcRewritingEngine.origin2rewriter.get(ssrcOrig);
        int sequenceNumber
            = rewriterPrimary.rewriteSequenceNumber(ssrcOrig, snOrig);

        if (sequenceNumber == SsrcRewritingEngine.INVALID_SEQNUM)
        {
            // Translation did not return anything useful. Dropping.
            return false;
        }
        else
        {
            pkt.setOriginalSequenceNumber((short) sequenceNumber);
            return true;
        }
    }

    /**
     * Calculates and returns the length of this interval. Note that
     * all 32 bits are used to represent the interval length because
     * an interval can span multiple cycles.
     *
     * @return the length of this interval.
     */
    public int length()
    {
        return extendedMaxOrig - extendedMinOrig;
    }

    /**
     *
     * @param primarySSRC
     * @param buf
     * @param off
     * @param len
     */
    private void rewriteRED(int primarySSRC, byte[] buf, int off, int len)
    {
        if (buf == null || buf.length == 0)
        {
            logWarn("The buffer is empty.");
            return;
        }

        if (buf.length < off + len)
        {
            logWarn("The buffer is invalid.");
            return;
        }

        // FIXME similar code can be found in the
        // REDFilterTransformEngine and in the REDTransformEngine and
        // in the SimulcastLayer.

        int idx = off; //beginning of RTP payload
        int pktCount = 0; //number of packets inside RED

        // 0                   1                   2                   3
        // 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        //+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        //|F|   block PT  |  timestamp offset         |   block length    |
        //+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        while ((buf[idx] & 0x80) != 0)
        {
            pktCount++;
            idx += 4;
        }

        idx = off; //back to beginning of RTP payload

        Map<Integer, Byte> ssrc2fec = getSsrcRewritingEngine().ssrc2fec;
        int sourceSSRC = ssrcRewriter.getSourceSSRC();
        int payloadOffset = idx + pktCount * 4 + 1 /* RED headers */;
        for (int i = 0; i <= pktCount; i++)
        {
            byte blockPT = (byte) (buf[idx] & 0x7f);
            int blockLen = (buf[idx + 2] & 0x03) << 8 | (buf[idx + 3]);

            if (ssrc2fec.get(sourceSSRC) == blockPT)
            {
                // TODO include only the FEC blocks that were
                // successfully rewritten.
                rewriteFEC(primarySSRC, buf, payloadOffset, blockLen);
            }

            idx += 4; // next RED header
            payloadOffset += blockLen;
        }
    }

    /**
     * Rewrites the SN base in the FEC Header.
     *
     * TODO do we need to change any other fields? Look at the
     * FECSender.
     *
     * @param buf
     * @param off
     * @param len
     * @return true if the FEC was successfully rewritten, false
     * otherwise
     */
    private boolean rewriteFEC(int sourceSSRC, byte[] buf, int off, int len)
    {
        if (buf == null || buf.length == 0)
        {
            logWarn("The buffer is empty.");
            return false;
        }

        if (buf.length < off + len)
        {
            logWarn("The buffer is invalid.");
            return false;
        }

        //  0                   1                   2                   3
        //  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |E|L|P|X|  CC   |M| PT recovery |            SN base            |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |                          TS recovery                          |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        // |        length recovery        |
        // +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
        short snBase = (short) (buf[off + 2] << 8 | buf[off + 3]);

        SsrcGroupRewriter rewriter
            = getSsrcRewritingEngine().origin2rewriter.get(sourceSSRC);
        int snRewritenBase
            = rewriter.rewriteSequenceNumber(sourceSSRC, snBase);


        if (snRewritenBase == SsrcRewritingEngine.INVALID_SEQNUM)
        {
            logInfo("We could not find a sequence number " +
                "interval for a FEC packet.");
            return false;
        }

        buf[off + 2] = (byte) (snRewritenBase & 0xff00 >> 8);
        buf[off + 3] = (byte) (snRewritenBase & 0x00ff);
        return true;
    }

    public SsrcGroupRewriter getSsrcGroupRewriter()
    {
        return ssrcRewriter.ssrcGroupRewriter;
    }

    public SsrcRewritingEngine getSsrcRewritingEngine()
    {
        return getSsrcGroupRewriter().ssrcRewritingEngine;
    }

    private void logInfo(String msg)
    {
        ssrcRewriter.logInfo(msg);
    }

    private void logWarn(String msg)
    {
        ssrcRewriter.logWarn(msg);
    }
}
