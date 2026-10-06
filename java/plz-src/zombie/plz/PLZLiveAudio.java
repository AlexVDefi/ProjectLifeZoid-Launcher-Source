package zombie.plz;

import fmod.javafmod;
import java.util.concurrent.ConcurrentHashMap;

public final class PLZLiveAudio {
    private static final long MODE_3D = 1170L;

    private static final class Slot {
        long sound;
        long channel;
        int rate;
        float x;
        float y;
        float z;
        float range = 20.0F;
        float volume;
        boolean placed;
    }

    private static final ConcurrentHashMap<String, Slot> slots = new ConcurrentHashMap<>();
    public static volatile long played;

    private PLZLiveAudio() {
    }

    private static void apply(Slot s) {
        if (s.channel == 0L) {
            return;
        }
        // FMOD's own rolloff is pushed past the screen's range; the caller's volume already carries the falloff
        javafmod.FMOD_Channel_Set3DMinMaxDistance(s.channel, s.range * 2.0F, s.range * 4.0F);
        javafmod.FMOD_Channel_Set3DAttributes(s.channel, s.x, s.y, s.z * 3.0F, 0.0F, 0.0F, 0.0F);
        javafmod.FMOD_Channel_SetVolume(s.channel, s.placed ? s.volume : 0.0F);
    }

    static void receive(String stream, int rate, short[] pcm) {
        if (pcm.length == 0 || rate < 8000 || rate > 48000 || !PLZLiveVideo.watching(stream)) {
            return;
        }
        Slot s = slots.computeIfAbsent(stream, k -> new Slot());
        synchronized (s) {
            if (s.sound != 0L && s.rate != rate) {
                close(s);
            }
            if (s.sound == 0L) {
                s.rate = rate;
                s.sound = javafmod.FMOD_System_CreateRAWPlaySound(MODE_3D, 2L, rate);
                if (s.sound == 0L) {
                    return;
                }
                s.channel = javafmod.FMOD_System_PlaySound(s.sound, false);
                if (s.channel != 0L) {
                    javafmod.FMOD_Channel_SetPriority(s.channel, 0);
                }
                apply(s);
            }
            javafmod.FMOD_System_RAWPlayData(s.sound, pcm, pcm.length);
            played += pcm.length;
        }
    }

    public static void place(String stream, float x, float y, float z, float range, float volume) {
        Slot s = slots.computeIfAbsent(stream, k -> new Slot());
        synchronized (s) {
            s.x = x;
            s.y = y;
            s.z = z;
            s.range = Math.max(1.0F, range);
            s.volume = Math.max(0.0F, volume);
            s.placed = true;
            apply(s);
        }
    }

    private static void close(Slot s) {
        if (s.channel != 0L) {
            javafmod.FMOD_Channel_Stop(s.channel);
            s.channel = 0L;
        }
        if (s.sound != 0L) {
            javafmod.FMOD_RAWPlaySound_Release(s.sound);
            s.sound = 0L;
        }
    }

    static void release(String stream) {
        Slot s = slots.remove(stream);
        if (s != null) {
            synchronized (s) {
                close(s);
            }
        }
    }
}
