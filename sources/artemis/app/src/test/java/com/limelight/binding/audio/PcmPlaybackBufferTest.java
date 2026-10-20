package com.limelight.binding.audio;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PcmPlaybackBufferTest {
    private static int accept(List<Short> out, short[] data, int offset, int count) {
        for(int i=0;i<count;i++) out.add(data[offset+i]);
        return count;
    }

    @Test public void pendingNativeArrayIsCopiedBeforeTheDecoderReusesIt() {
        PcmPlaybackBuffer buffer = new PcmPlaybackBuffer(100);
        List<Short> out = new ArrayList<>();
        short[] reused = {1,2,3,4};
        buffer.write(reused,(p,o,n)->accept(out,p,o,2));
        reused[2]=99;reused[3]=99;
        buffer.write(new short[]{5,6},(p,o,n)->accept(out,p,o,n));
        assertEquals(Arrays.asList((short)1,(short)2,(short)3,(short)4,(short)5,(short)6),out);
        assertEquals(0,buffer.queuedSamples());
    }

    @Test public void repeatedZeroWritesNeverBusyWaitOrLoseTheQueue() {
        PcmPlaybackBuffer buffer = new PcmPlaybackBuffer(100);
        AtomicInteger calls = new AtomicInteger();
        PcmPlaybackBuffer.Writer full = (p,o,n)->{calls.incrementAndGet();return 0;};
        buffer.write(new short[]{1,2},full);
        buffer.write(new short[]{3,4},full);
        assertEquals(2,calls.get());
        assertEquals(4,buffer.queuedSamples());
        assertEquals(0,buffer.droppedSamples());
        List<Short> out=new ArrayList<>();
        buffer.write(new short[0],(p,o,n)->accept(out,p,o,n));
        assertEquals(Arrays.asList((short)1,(short)2,(short)3,(short)4),out);
    }

    @Test public void genuineOverflowPreservesAnAlreadyStartedPacket() {
        PcmPlaybackBuffer buffer = new PcmPlaybackBuffer(6);
        List<Short> out=new ArrayList<>();
        buffer.write(new short[]{1,2,3,4},(p,o,n)->accept(out,p,o,2));
        buffer.write(new short[]{5,6,7,8},(p,o,n)->0);
        buffer.write(new short[]{9,10,11,12},(p,o,n)->0);
        assertEquals(6,buffer.queuedSamples());
        assertEquals(4,buffer.droppedSamples());
        buffer.write(new short[0],(p,o,n)->accept(out,p,o,n));
        assertEquals(Arrays.asList((short)1,(short)2,(short)3,(short)4,
                (short)9,(short)10,(short)11,(short)12),out);
    }

    @Test public void deviceFailureAndExplicitClearNeverReplayOldAudio() {
        PcmPlaybackBuffer buffer = new PcmPlaybackBuffer(100);
        buffer.write(new short[]{1,2},(p,o,n)->0);
        assertEquals(-6,buffer.write(new short[]{3,4},(p,o,n)->-6));
        assertEquals(0,buffer.queuedSamples());
        buffer.write(new short[]{5,6},(p,o,n)->0);
        buffer.clear();
        List<Short> out=new ArrayList<>();
        buffer.write(new short[]{7,8},(p,o,n)->accept(out,p,o,n));
        assertEquals(Arrays.asList((short)7,(short)8),out);
    }

    @Test public void callbackWorkIsBoundedEvenIfTheDeviceOnlyAcceptsOneSample() {
        PcmPlaybackBuffer buffer=new PcmPlaybackBuffer(200);
        AtomicInteger calls=new AtomicInteger();
        PcmPlaybackBuffer.Writer tiny=(p,o,n)->{calls.incrementAndGet();return 1;};
        buffer.write(new short[100],tiny);
        buffer.write(new short[0],tiny);
        assertEquals(9,calls.get());
        assertEquals(91,buffer.queuedSamples());
    }

    @Test public void oversizedPacketCannotAllocateAnUnboundedBacklog() {
        PcmPlaybackBuffer buffer=new PcmPlaybackBuffer(4);
        buffer.write(new short[1000],(p,o,n)->0);
        assertEquals(0,buffer.queuedSamples());
        assertEquals(1000,buffer.droppedSamples());
    }
}
