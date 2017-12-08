package org.jitsi.impl.neomedia.transform.fec;

import org.junit.*;

import java.util.*;

import static org.junit.Assert.*;

/**
 * @author bbaldino
 */
public class FlexFec03MaskTest
{
    @Test
    public void testTooShortBuffer()
    {
        final int K_BIT_0 = 0 << 7;
        final byte[] maskData = {
            K_BIT_0 | 0x00
        };

        try
        {
            FlexFec03Mask mask = new FlexFec03Mask(maskData, 0, 0);
            fail("Expected MalformedMaskException");
        }
        catch (FlexFec03Mask.MalformedMaskException e)
        {

        }
    }

    @Test
    public void testCreateFlexFecMaskShort()
    {
        List<Integer> expectedProtectedSeqNums = Arrays.asList(0, 1, 3, 5, 14);
        int baseSeqNum = 0;
        FlexFec03Mask mask = new FlexFec03Mask(baseSeqNum, expectedProtectedSeqNums);

        List<Integer> protectedSeqNums = mask.getProtectedSeqNums();
        assertEquals(expectedProtectedSeqNums, protectedSeqNums);
    }

    @Test
    public void testSeqNumRollover()
    {
        List<Integer> expectedProtectedSeqNums = Arrays.asList(65530, 65531, 65533, 65535, 5, 6);
        int baseSeqNum = 65530;

        FlexFec03Mask mask = new FlexFec03Mask(baseSeqNum, expectedProtectedSeqNums);

        List<Integer> protectedSeqNums = mask.getProtectedSeqNums();
        assertEquals(expectedProtectedSeqNums, protectedSeqNums);
    }

    @Test
    public void testCreateFlexFecMaskMed()
    {
        List<Integer> expectedProtectedSeqNums = Arrays.asList(0, 1, 3, 5, 14, 15, 16, 20, 24, 45);
        int baseSeqNum = 0;
        FlexFec03Mask mask = new FlexFec03Mask(baseSeqNum, expectedProtectedSeqNums);

        List<Integer> protectedSeqNums = mask.getProtectedSeqNums();
        assertEquals(expectedProtectedSeqNums, protectedSeqNums);
    }

    @Test
    public void testCreateFlexFecMaskLong()
    {
        List<Integer> expectedProtectedSeqNums =
            Arrays.asList(0, 1, 3, 5, 14, 15, 20, 24, 45, 108);
        int baseSeqNum = 0;
        FlexFec03Mask mask = new FlexFec03Mask(baseSeqNum, expectedProtectedSeqNums);

        List<Integer> protectedSeqNums = mask.getProtectedSeqNums();
        assertEquals(expectedProtectedSeqNums, protectedSeqNums);
    }

    /**
     * Since we've already verified that FlexFec03Mask generates a mask correctly
     * from a given set of sequence numbers, we can use that in the following
     * tests to create the expected mask from a set of sequence numbers via
     * the FlexFec03Mask methods we tested above
     */
    private FlexFec03BitSet getMask(int baseSeqNum, List<Integer> protectedSeqNums)
    {
        FlexFec03Mask m = new FlexFec03Mask(baseSeqNum, protectedSeqNums);
        return m.getMaskWithKBits();
    }

    private void verifyMask(FlexFec03BitSet expected, FlexFec03BitSet actual)
    {
        assertEquals(expected.sizeBits(), actual.sizeBits());
        for (int i = 0; i < expected.sizeBits(); ++i)
        {
            assertEquals(expected.get(i), actual.get(i));
        }
    }

    @Test
    public void testFlexFecMaskShortFromBuffer()
        throws Exception
    {
        List<Integer> expectedProtectedSeqNums = Arrays.asList(
            0, 2, 5, 9, 10, 12, 14
        );

        FlexFec03BitSet expectedMask = getMask(0, expectedProtectedSeqNums);

        FlexFec03Mask mask = new FlexFec03Mask(expectedMask.toByteArray(), 0, 0);
        verifyMask(expectedMask, mask.getMaskWithKBits());
    }

    @Test
    public void testFlexFecMaskMedFromBuffer()
        throws Exception
    {
        List<Integer> expectedProtectedSeqNums = Arrays.asList(
            0, 2, 5, 9, 10, 12, 14, 15, 22, 32, 45
        );

        FlexFec03BitSet expectedMask = getMask(0, expectedProtectedSeqNums);

        FlexFec03Mask mask = new FlexFec03Mask(expectedMask.toByteArray(), 0, 0);
        verifyMask(expectedMask, mask.getMaskWithKBits());
    }

    @Test
    public void testFlexFecMaskLong()
        throws Exception
    {
        List<Integer> expectedProtectedSeqNums = Arrays.asList(
            0, 2, 5, 9, 10, 12, 14, 15, 22, 32, 45, 46, 56, 66, 76, 90, 108
        );

        FlexFec03BitSet expectedMask = getMask(0, expectedProtectedSeqNums);

        FlexFec03Mask mask = new FlexFec03Mask(expectedMask.toByteArray(), 0, 0);
        verifyMask(expectedMask, mask.getMaskWithKBits());
    }
}