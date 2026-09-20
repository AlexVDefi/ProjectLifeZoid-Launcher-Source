package zombie.core.raknet;

import fmod.FMODRecordPosition;
import fmod.FMODSoundData;
import fmod.FMOD_DriverInfo;
import fmod.FMOD_RESULT;
import fmod.javafmod;
import fmod.javafmodJNI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Semaphore;
import se.krka.kahlua.vm.JavaFunction;
import se.krka.kahlua.vm.KahluaTable;
import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.Platform;
import zombie.characters.IsoPlayer;
import zombie.core.Core;
import zombie.core.network.ByteBufferReader;
import zombie.core.network.ByteBufferWriter;
import zombie.debug.DebugType;
import zombie.debug.LogSeverity;
import zombie.input.GameKeyboard;
import zombie.inventory.InventoryItem;
import zombie.inventory.types.Radio;
import zombie.iso.IsoCell;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoObject;
import zombie.iso.IsoUtils;
import zombie.iso.objects.IsoRadio;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.network.FakeClientManager;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.network.PacketTypes;
import zombie.network.ServerOptions;
import zombie.plz.PLZChannelProbe;
import zombie.plz.PLZFixes;
import zombie.plz.PLZVoice;
import zombie.plz.PLZVoiceChanger;
import zombie.radio.devices.DeviceData;
import zombie.vehicles.BaseVehicle;
import zombie.vehicles.VehiclePart;

public class VoiceManager {
    private static final int FMOD_SOUND_MODE = 1154;
    public static final int modePPT = 1;
    public static final int modeVAD = 2;
    public static final int modeMute = 3;
    public static final int VADModeQuality = 1;
    public static final int VADModeLowBitrate = 2;
    public static final int VADModeAggressive = 3;
    public static final int VADModeVeryAggressive = 4;
    public static final int AGCModeAdaptiveAnalog = 1;
    public static final int AGCModeAdaptiveDigital = 2;
    public static final int AGCModeFixedDigital = 3;
    private static final int bufferSize = 192;
    private static final int complexity = 1;
    private static boolean serverVOIPEnable = true;
    private static int sampleRate = 16000;
    private static int period = 300;
    private static int buffering = 8000;
    private static float minDistance;
    private static float maxDistance;
    private static boolean is3D;
    private boolean isEnable = true;
    private boolean isModeVad;
    private boolean isModePpt;
    private int vadMode = 3;
    private int agcMode = 2;
    private int volumeMic;
    private int volumePlayers;
    public static boolean voipDisabled;
    private boolean isServer;
    private static byte[] fmodReceiveBuffer;
    private final FMODSoundData fmodSoundData = new FMODSoundData();
    private final FMODRecordPosition fmodRecordPosition = new FMODRecordPosition();
    private int fmodSoundDataError;
    private int fmodVoiceRecordDriverId;
    private long fmodChannelGroup;
    private long fmodRecordSound;
    private Semaphore recDevSemaphore;
    private boolean initialiseRecDev;
    private boolean initialisedRecDev;
    private long indicatorIsVoice;
    private Thread thread;
    private boolean quit;
    private long timeLast;
    private final boolean isDebug = false;
    private final boolean isDebugLoopback = false;
    private final boolean isDebugLoopbackLong = false;
    public static VoiceManager instance = new VoiceManager();
    byte[] buf = new byte[192];
    private final Object notifier = new Object();
    private boolean isClient;
    private boolean testingMicrophone;
    private long testingMicrophoneMs;
    private static long timestamp;
    private static boolean plzPrivateCall = false;

    private static volatile String plzRadioPttBinding = null;

    // The peers serviced by plzServiceRadioOnlyPeers on the last pass, and how many voice frames
    // have arrived from each peer since this client joined. Both exist so the MP suite can tell
    // "the channels line up" from "the audio actually reaches this machine" - which is the exact
    // pair of facts the walkie was failing on, and which look identical from Lua otherwise.
    // Written on the voice thread and read from Lua on the main one, hence the locks.
    private static final ArrayList<Short> plzRadioOnlyPeers = new ArrayList<>();
    private static final java.util.HashMap<Short, Integer> plzFrameCounts = new java.util.HashMap<>();

    // THE MEGAPHONE HISS, per speaker, on the LISTENER's machine.
    //
    // A walkie sounds like a walkie because the DEVICE is emitting RadioStatic
    // the whole time it is on - see DeviceData's sound pass - and not because
    // anything is done to the voice itself. A megaphone has no device, so the
    // hiss is played from the speaker's own emitter for as long as their frames
    // keep arriving, which puts it in the world at the right place and lets the
    // engine's own 3D falloff carry it.
    //
    // KEYED BY ONLINE ID AND STOPPED WHEN THE VOICE STOPS. The handle is what
    // lets it be stopped at all; without it a hiss started here would run until
    // the listener left the area, which is the leak the walkie's own
    // radioLoopSound handle exists to avoid.
    private static final java.util.HashMap<Short, Long> plzMegaphoneLoops = new java.util.HashMap<>();
    private static final String PLZ_MEGAPHONE_STATIC = "RadioStatic";

    public static VoiceManager getInstance() {
        return instance;
    }

    public void DeinitRecSound() {
        this.initialisedRecDev = false;
        if (this.fmodRecordSound != 0L) {
            javafmod.FMOD_RecordSound_Release(this.fmodRecordSound);
            this.fmodRecordSound = 0L;
        }

        fmodReceiveBuffer = null;
    }

    public void ResetRecSound() {
        if (this.initialisedRecDev && this.fmodRecordSound != 0L) {
            int result = javafmod.FMOD_System_RecordStop(this.fmodVoiceRecordDriverId);
            if (result != FMOD_RESULT.FMOD_OK.ordinal()) {
                DebugType.Voice.warn("FMOD_System_RecordStop result=%d", result);
            }
        }

        this.DeinitRecSound();
        this.fmodRecordSound = javafmod.FMOD_System_CreateRecordSound(this.fmodVoiceRecordDriverId, 1096L, 2L, sampleRate, this.agcMode);
        if (this.fmodRecordSound == 0L) {
            DebugType.Voice.warn("FMOD_System_CreateSound result=%d", this.fmodRecordSound);
        }

        javafmod.FMOD_System_SetRecordVolume(1L - Math.round(Math.pow(1.4, 11 - this.volumeMic)));
        if (this.initialiseRecDev) {
            int result = javafmod.FMOD_System_RecordStart(this.fmodVoiceRecordDriverId, this.fmodRecordSound, true);
            if (result != FMOD_RESULT.FMOD_OK.ordinal()) {
                DebugType.Voice.warn("FMOD_System_RecordStart result=%d", result);
            }
        }

        javafmod.FMOD_System_SetVADMode(this.vadMode - 1);
        fmodReceiveBuffer = new byte[2048];

        // PLZ. The grain length is a fraction of a second, so it is a different number of
        // samples on an 8 kHz host than on a 24 kHz one. Done here rather than once at load
        // because this is the point where the rate is settled AND the capture buffer is fresh,
        // and a stale ring read at the wrong delay is a burst of noise on the first word.
        PLZVoiceChanger.setSampleRate(sampleRate);
        PLZVoiceChanger.reset();

        this.initialisedRecDev = true;
    }

    public void VoiceRestartClient(boolean isEnable) {
        if (GameClient.connection != null) {
            if (isEnable) {
                this.loadConfig();
                this.VoiceConnectReq(GameClient.connection.getConnectedGUID());
            } else {
                this.threadSafeCode(this::DeinitRecSound);
                this.VoiceConnectClose(GameClient.connection.getConnectedGUID());
                this.loadConfig();
            }
        } else {
            this.loadConfig();
            if (isEnable) {
                this.InitRecDeviceForTest();
            } else {
                this.threadSafeCode(this::DeinitRecSound);
            }
        }
    }

    void VoiceInitClient() {
        this.isServer = false;
        this.recDevSemaphore = new Semaphore(1);
        fmodReceiveBuffer = null;
        RakVoice.RVInit(192);
        RakVoice.SetComplexity(1);
    }

    void VoiceInitServer(boolean enable, int sampleRate, int period, int complexity, int buffering, double minDistance, double maxDistance, boolean is3D) {
        this.isServer = true;
        if (!(period == 2 | period == 5 | period == 10 | period == 20 | period == 40 | period == 60)) {
            DebugType.Voice.error("Invalid period=%d", period);
        } else if (!(sampleRate == 8000 | sampleRate == 16000 | sampleRate == 24000)) {
            DebugType.Voice.error("Invalid sample rate=%d", sampleRate);
        } else if (complexity < 0 | complexity > 10) {
            DebugType.Voice.error("Invalid quality=%d", complexity);
        } else if (buffering < 0 | buffering > 32000) {
            DebugType.Voice.error("Invalid buffering=%d", buffering);
        } else {
            VoiceManager.sampleRate = sampleRate;
            RakVoice.RVInitServer(enable, sampleRate, period, complexity, buffering, (float)minDistance, (float)maxDistance, is3D);
        }
    }

    void VoiceConnectAccept(long uuid) {
        if (this.isEnable) {
            DebugType.Voice.debugln("uuid=%x", uuid);
        }
    }

    void InitRecDeviceForTest() {
        this.threadSafeCode(this::ResetRecSound);
    }

    void VoiceOpenChannelReply(long uuid, ByteBufferReader buf) {
        if (this.isEnable) {
            DebugType.Voice.debugln("uuid=%d", uuid);
            if (this.isServer) {
                return;
            }

            try {
                if (GameClient.client) {
                    serverVOIPEnable = buf.getInt() != 0;
                    sampleRate = buf.getInt();
                    period = buf.getInt();
                    buf.getInt();
                    buffering = buf.getInt();
                    minDistance = buf.getFloat();
                    maxDistance = buf.getFloat();
                    is3D = buf.getInt() != 0;
                } else {
                    serverVOIPEnable = RakVoice.GetServerVOIPEnable();
                    sampleRate = RakVoice.GetSampleRate();
                    period = RakVoice.GetSendFramePeriod();
                    buffering = RakVoice.GetBuffering();
                    minDistance = RakVoice.GetMinDistance();
                    maxDistance = RakVoice.GetMaxDistance();
                    is3D = RakVoice.GetIs3D();
                }
            } catch (Exception e) {
                DebugType.Voice.printException(e, "RakVoice params set failed", LogSeverity.Error);
                return;
            }

            DebugType.Voice
                .debugln(
                    "enabled=%b, sample-rate=%d, period=%d, complexity=%d, buffering=%d, is3D=%b", serverVOIPEnable, sampleRate, period, 1, buffering, is3D
                );

            try {
                this.recDevSemaphore.acquire();
            } catch (InterruptedException e) {
                DebugType.General.printException(e, LogSeverity.Error);
            }

            int mode = is3D ? 1170 : 1154;

            for (VoiceManagerData d : VoiceManagerData.data) {
                if (d.userplaysound != 0L) {
                    javafmod.FMOD_Sound_SetMode(d.userplaysound, mode);
                }
            }

            long result = javafmod.FMOD_System_SetRawPlayBufferingPeriod(buffering);
            if (result != FMOD_RESULT.FMOD_OK.ordinal()) {
                DebugType.Voice.warn("FMOD_System_SetRawPlayBufferingPeriod result=%d", result);
            }

            this.ResetRecSound();
            this.recDevSemaphore.release();
        }
    }

    public void VoiceConnectReq(long uuid) {
        if (this.isEnable) {
            DebugType.Voice.debugln("uuid=%x", uuid);
            VoiceManagerData.data.clear();
            RakVoice.RequestVoiceChannel(uuid);
        }
    }

    public void VoiceConnectClose(long uuid) {
        if (this.isEnable) {
            DebugType.Voice.debugln("uuid=%x", uuid);
            RakVoice.CloseVoiceChannel(uuid);
        }
    }

    public void setMode(int mode) {
        if (mode == 3) {
            this.isModeVad = false;
            this.isModePpt = false;
        } else if (mode == 1) {
            this.isModeVad = false;
            this.isModePpt = true;
        } else if (mode == 2) {
            this.isModeVad = true;
            this.isModePpt = false;
        }
    }

    public void setVADMode(int mode) {
        if (!(mode < 1 | mode > 4)) {
            this.vadMode = mode;
            if (this.initialisedRecDev) {
                this.threadSafeCode(() -> javafmod.FMOD_System_SetVADMode(this.vadMode - 1));
            }
        }
    }

    public void setAGCMode(int mode) {
        if (!(mode < 1 | mode > 3)) {
            this.agcMode = mode;
            if (this.initialisedRecDev) {
                this.threadSafeCode(this::ResetRecSound);
            }
        }
    }

    public void setVolumePlayers(int volume) {
        if (!(volume < 0 | volume > 11)) {
            if (volume <= 10) {
                this.volumePlayers = volume;
            } else {
                this.volumePlayers = 12;
            }

            if (this.initialisedRecDev) {
                ArrayList<VoiceManagerData> data = VoiceManagerData.data;

                for (int i = 0; i < data.size(); i++) {
                    VoiceManagerData d = data.get(i);
                    if (d != null && d.userplaychannel != 0L) {
                        javafmod.FMOD_Channel_SetVolume(d.userplaychannel, (float)(this.volumePlayers * 0.2));
                    }
                }
            }
        }
    }

    public void setVolumeMic(int volume) {
        if (!(volume < 0 | volume > 11)) {
            if (volume <= 10) {
                this.volumeMic = volume;
            } else {
                this.volumeMic = 12;
            }

            if (this.initialisedRecDev) {
                this.threadSafeCode(() -> javafmod.FMOD_System_SetRecordVolume(1L - Math.round(Math.pow(1.4, 11 - this.volumeMic))));
            }
        }
    }

    public static void playerSetMute(String username) {
        ArrayList<IsoPlayer> players = GameClient.instance.getPlayers();

        for (int i1 = 0; i1 < players.size(); i1++) {
            IsoPlayer player = players.get(i1);
            if (username.equals(player.username)) {
                VoiceManagerData d = VoiceManagerData.get(player.onlineId);
                d.userplaymute = !d.userplaymute;
                player.isVoiceMute = d.userplaymute;
                break;
            }
        }
    }

    public static boolean playerGetMute(String username) {
        ArrayList<IsoPlayer> players = GameClient.instance.getPlayers();

        for (int i1 = 0; i1 < players.size(); i1++) {
            IsoPlayer player = players.get(i1);
            if (username.equals(player.username)) {
                return VoiceManagerData.get(player.onlineId).userplaymute;
            }
        }

        return true;
    }

    public void LuaRegister(Platform platform, KahluaTable environment) {
        KahluaTable table = platform.newTable();
        table.rawset("playerSetMute", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                Object arg1 = callFrame.get(1);
                VoiceManager.playerSetMute((String)arg1);
                return 1;
            }
        });
        table.rawset("playerGetMute", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                Object arg1 = callFrame.get(1);
                callFrame.push(VoiceManager.playerGetMute((String)arg1));
                return 1;
            }
        });
        table.rawset("RecordDevices", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                if (!Core.soundDisabled && !VoiceManager.voipDisabled) {
                    int numDevices = javafmod.FMOD_System_GetRecordNumDrivers();
                    KahluaTable recordDevices = callFrame.getPlatform().newTable();

                    for (int i = 0; i < numDevices; i++) {
                        FMOD_DriverInfo info = new FMOD_DriverInfo();
                        javafmod.FMOD_System_GetRecordDriverInfo(i, info);
                        recordDevices.rawset(i + 1, info.name);
                    }

                    callFrame.push(recordDevices);
                    return 1;
                } else {
                    KahluaTable recordDevices = callFrame.getPlatform().newTable();
                    callFrame.push(recordDevices);
                    return 1;
                }
            }
        });
        table.rawset("setPrivateCall", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                Object arg1 = callFrame.get(0);
                VoiceManager.plzPrivateCall = arg1 instanceof Boolean && (Boolean)arg1;
                VoiceManager.plzRepublishChannels();
                return 1;
            }
        });
        table.rawset("getPrivateCall", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                callFrame.push(VoiceManager.plzPrivateCall);
                return 1;
            }
        });
        table.rawset("setRadioPttBinding", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                // get(0), NOT get(1). LuaCallFrame is ZERO-BASED, and this read is the whole of
                // whether the radio key works: get(1) is the second argument, which nothing
                // passes, so the binding was set to null on every call and plzIsRadioPttDown
                // answered false forever. The key looked bound, the panel said it was armed, and
                // the mic never opened. Same trap, same disguise, as setPrivateCall and
                // setVoiceConfig - see .claude/PLZ-VOICE.md section 0.
                Object arg1 = callFrame.get(0);
                VoiceManager.plzRadioPttBinding = arg1 instanceof String s && !s.isEmpty() ? s : null;
                return 1;
            }
        });
        table.rawset("republishChannels", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                VoiceManager.plzRepublishChannels();
                return 1;
            }
        });
        // WHETHER THIS CLIENT WOULD ACTUALLY PLAY THAT PEER'S RADIO AUDIO, which getPeerRadio
        // cannot answer: it reports that the channels and ranges line up, and for a peer far
        // enough away that GameClient has timed their character out the engine used to agree
        // and then never ask RakVoice for a single frame. `loaded` is the difference.
        table.rawset("getRadioAudio", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                // get(0). Zero-based, like every other read on this table.
                KahluaTable info = callFrame.getPlatform().newTable();
                Object arg1 = callFrame.get(0);
                if (!(arg1 instanceof Double onlineId)) {
                    info.rawset("known", false);
                    callFrame.push(info);
                    return 1;
                }

                short id = (short)onlineId.intValue();
                boolean loaded = false;
                if (GameClient.client && GameClient.instance != null) {
                    ArrayList<IsoPlayer> players = GameClient.instance.getPlayers();
                    for (int i = 0; i < players.size(); i++) {
                        if (players.get(i).onlineId == id) {
                            loaded = true;
                            break;
                        }
                    }
                }

                boolean serviced;
                synchronized (plzRadioOnlyPeers) {
                    serviced = plzRadioOnlyPeers.contains(id);
                }

                int frames;
                synchronized (plzFrameCounts) {
                    Integer seen = plzFrameCounts.get(id);
                    frames = seen == null ? 0 : seen;
                }

                info.rawset("known", true);
                info.rawset("loaded", loaded);
                info.rawset("serviced", serviced);
                // Without this the voice thread never runs its receive pass at all, so `serviced`
                // reads false for a reason that has nothing to do with the radio.
                info.rawset("voip", serverVOIPEnable);
                // AN ADMIN HEARS EVERYONE ANYWAY, and checkForNearbyRadios says so by returning
                // the proximity entry before it ever looks at a radio. So `linked` is
                // structurally false for a hear-all listener, and a test that read it as "the
                // radio path is broken" would be reading the wrong thing entirely.
                boolean hearAll = false;
                IsoPlayer me = IsoPlayer.getInstance();
                if (me != null && me.onlineId != -1) {
                    hearAll = VoiceManagerData.get(me.onlineId).isCanHearAll || me.canHearAll();
                }
                info.rawset("hearAll", hearAll);
                info.rawset("linked", VoiceManager.instance.plzRadioLink(VoiceManagerData.get(id)) != null);
                info.rawset("frames", (double)frames);
                callFrame.push(info);
                return 1;
            }
        });
        table.rawset("setVoiceConfig", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                Object arg1 = callFrame.get(0);
                if (arg1 instanceof KahluaTable cfg) {
                    PLZVoice.setConfig(
                        plzConfigBool(cfg, "floors", true),
                        plzConfigBool(cfg, "modes", true),
                        plzConfigFloat(cfg, "whisper", PLZVoice.getWhisperFraction()),
                        plzConfigFloat(cfg, "normal", PLZVoice.getNormalFraction()),
                        plzConfigFloat(cfg, "shout", PLZVoice.getShoutFraction()),
                        plzConfigFloat(cfg, "megaphone", PLZVoice.getMegaphoneFraction()),
                        plzConfigFloat(cfg, "falloff", PLZVoice.getFalloffExponent()),
                        plzConfigFloat(cfg, "gain", PLZVoice.getGain())
                    );
                    VoiceManager.plzRepublishChannels();
                }

                return 1;
            }
        });
        table.rawset("getVoiceInfo", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                KahluaTable info = callFrame.getPlatform().newTable();
                IsoPlayer me = IsoPlayer.getInstance();
                int index = me == null ? 0 : me.getIndex();
                int mode = PLZVoice.getMode(index);
                info.rawset("mode", (double)mode);
                info.rawset("floors", PLZVoice.isFloorsEnabled());
                info.rawset("modes", PLZVoice.isModesEnabled());
                info.rawset("maxDistance", (double)maxDistance);
                info.rawset("range", (double)PLZVoice.rangeForMode(mode, maxDistance));
                                info.rawset("channel", (double)(me == null ? PLZVoice.CHANNEL_NONE : PLZVoice.entryChannel(me.getZi())));
                info.rawset("minDistance", (double)minDistance);

                info.rawset("volumeAt2", (double)PLZVoice.volumeFor(mode, 2.0F, minDistance, maxDistance));
                info.rawset("volumeAt20", (double)PLZVoice.volumeFor(mode, 20.0F, minDistance, maxDistance));
                info.rawset("gain", (double)PLZVoice.getGain());

                info.rawset("isClient", GameClient.client);
                info.rawset("hasConnection", GameClient.connection != null);
                info.rawset("numPlayers", (double)IsoPlayer.numPlayers);
                info.rawset("player0", IsoPlayer.numPlayers > 0 && IsoPlayer.players[0] != null);
                info.rawset("onlineId", (double)(me == null ? -1 : me.onlineId));
                if (me != null && me.onlineId != -1) {
                    VoiceManagerData mineData = VoiceManagerData.get(me.onlineId);
                    synchronized (mineData.radioData) {
                        info.rawset("myEntries", (double)mineData.radioData.size());
                    }
                }
                callFrame.push(info);
                return 1;
            }
        });
        table.rawset("getPeerVoice", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                KahluaTable info = callFrame.getPlatform().newTable();
                Object arg1 = callFrame.get(0);
                IsoPlayer me = IsoPlayer.getInstance();
                if (!(arg1 instanceof Double onlineId) || me == null || me.onlineId == -1) {
                    info.rawset("known", false);
                    callFrame.push(info);
                    return 1;
                }

                VoiceManagerData theirs = VoiceManagerData.get((short)onlineId.intValue());
                VoiceManagerData mine = VoiceManagerData.get(me.onlineId);
                synchronized (theirs.radioData) {
                    synchronized (mine.radioData) {
                        if (theirs.radioData.isEmpty() || mine.radioData.isEmpty()) {
                            info.rawset("known", false);
                            callFrame.push(info);
                            return 1;
                        }

                        VoiceManagerData.RadioData them = theirs.radioData.get(0);
                        VoiceManagerData.RadioData us = mine.radioData.get(0);
                        float allowed = PLZVoice.audibleRange(us.freq, them.freq, them.distance, maxDistance);
                        float gate = PLZVoice.gateRange(us.freq, them.freq, them.distance, maxDistance);
                        float dx = us.x - them.x;
                        float dy = us.y - them.y;
                        float apart = (float)Math.sqrt(dx * dx + dy * dy);

                        info.rawset("known", true);
                        info.rawset("channel", (double)them.freq);
                        info.rawset("distance", (double)them.distance);
                        info.rawset("entries", (double)theirs.radioData.size());
                        info.rawset("myChannel", (double)us.freq);
                        info.rawset("apart", (double)apart);
                        info.rawset("allowed", (double)allowed);
                        info.rawset("wouldHear", apart < allowed);

                        // TWO ANSWERS, BECAUSE THERE ARE NOW TWO TESTS. allowed/wouldHear are the
                        // AUDIBLE range - what the falloff will do with this speaker, and what
                        // every existing assertion in the MP suite is about, so neither moved.
                        // gate/wouldRoute are what checkForNearbyRadios actually decides on, which
                        // is deliberately wider by PLZVoice.ROUTING_DRIFT_TILES. A run where
                        // wouldRoute is true and wouldHear is false is the normal, healthy case
                        // for somebody who has just walked out of earshot: still carried, already
                        // silent, and silent on a curve rather than a cliff.
                        info.rawset("gate", (double)gate);
                        info.rawset("wouldRoute", apart < gate);

                        int floor = PLZVoice.floorForChannel(them.freq);
                        info.rawset("hasFloor", floor != PLZVoice.NO_FLOOR);
                        if (floor != PLZVoice.NO_FLOOR) {
                            info.rawset("floor", (double)floor);
                        }
                    }
                }

                callFrame.push(info);
                return 1;
            }
        });
        table.rawset("getPeerRadio", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                KahluaTable info = callFrame.getPlatform().newTable();
                // get(0). The zero-based trap again, and here it wears the worst disguise of the
                // three: rejecting the id answers known=false for every peer, which reads exactly
                // like the routing array never crossing the wire. getPeerVoice was fixed for this
                // and this one, written later for a suite that had never been run, repeated it.
                Object arg1 = callFrame.get(0);
                IsoPlayer me = IsoPlayer.getInstance();
                if (!(arg1 instanceof Double onlineId) || me == null || me.onlineId == -1) {
                    info.rawset("known", false);
                    callFrame.push(info);
                    return 1;
                }

                VoiceManagerData theirs = VoiceManagerData.get((short)onlineId.intValue());
                VoiceManagerData mine = VoiceManagerData.get(me.onlineId);
                synchronized (theirs.radioData) {
                    synchronized (mine.radioData) {
                        info.rawset("known", true);
                        info.rawset("myEntries", (double)mine.radioData.size());
                        info.rawset("theirEntries", (double)theirs.radioData.size());

                        boolean matched = false;
                        for (int i = 1; i < mine.radioData.size() && !matched; i++) {
                            VoiceManagerData.RadioData us = mine.radioData.get(i);
                            if (PLZVoice.floorForChannel(us.freq) != PLZVoice.NO_FLOOR) {
                                continue;
                            }
                            for (int j = 1; j < theirs.radioData.size(); j++) {
                                VoiceManagerData.RadioData them = theirs.radioData.get(j);
                                if (us.freq != them.freq) {
                                    continue;
                                }
                                float dx = us.x - them.x;
                                float dy = us.y - them.y;
                                float apart = (float)Math.sqrt(dx * dx + dy * dy);
                                info.rawset("channel", (double)them.freq);
                                info.rawset("theirRange", (double)them.distance);
                                info.rawset("apart", (double)apart);
                                if (apart < them.distance) {
                                    info.rawset("wouldHear", true);
                                    matched = true;
                                    break;
                                }
                                info.rawset("wouldHear", false);
                            }
                        }
                        info.rawset("matched", matched);
                    }
                }

                callFrame.push(info);
                return 1;
            }
        });
        // PLZ VOICE CHANGER. Two functions, because Lua has exactly two questions to ask:
        // what may I do, and do this. The gate is answered by Java rather than worked out in
        // Lua so the row in the management window and the shifter itself can never disagree
        // about who is allowed - see PLZVoiceChanger.ALLOWED_ACCOUNTS.
        //
        // get(0), NOT get(1). LuaCallFrame is zero-based; see setRadioPttBinding above for what
        // that cost the last time it was got wrong.
        table.rawset("setVoiceChanger", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                Object arg1 = callFrame.get(0);
                Object arg2 = callFrame.get(1);
                boolean on = arg1 instanceof Boolean && (Boolean)arg1;

                // The preset lands BEFORE the enable, so switching straight from one preset to
                // another while armed does not pass through the old one for a frame.
                boolean took = true;
                if (arg2 instanceof Double preset) {
                    took = PLZVoiceChanger.setPreset((int)Math.round(preset));
                }
                took = PLZVoiceChanger.setEnabled(on) && took;

                callFrame.push(took);
                return 1;
            }
        });
        table.rawset("getVoiceChanger", new JavaFunction() {
            @Override
            public int call(LuaCallFrame callFrame, int nArguments) {
                KahluaTable info = callFrame.getPlatform().newTable();
                info.rawset("allowed", PLZVoiceChanger.localIsAllowed());
                info.rawset("enabled", PLZVoiceChanger.isEnabled());
                info.rawset("preset", (double)PLZVoiceChanger.getPreset());
                info.rawset("presets", (double)PLZVoiceChanger.PRESET_COUNT);
                info.rawset("ratio", (double)PLZVoiceChanger.activeRatio());
                info.rawset("window", (double)PLZVoiceChanger.getWindow());
                callFrame.push(info);
                return 1;
            }
        });
        environment.rawset("VoiceManager", table);
    }

    // Two people in one car are unambiguously in earshot, and the routing gate cannot know it:
    // their entries publish on independent timers, so at speed the positions it subtracts are a
    // full interval of travel apart, and a z that rounds differently between the two zeroes
    // audibleRange outright - a mute no drift allowance can reach.
    private static boolean plzSharesVehicle(IsoPlayer me, IsoPlayer them) {
        if (me == null || them == null) {
            return false;
        }
        BaseVehicle mine = me.getVehicle();
        return mine != null && mine == them.getVehicle();
    }

    private static int plzSpeakerMode(VoiceManagerData speaker) {
        synchronized (speaker.radioData) {
            if (speaker.radioData.isEmpty()) {
                return PLZVoice.MODE_NORMAL;
            }
            return PLZVoice.bucketMode(speaker.radioData.get(0).distance, maxDistance);
        }
    }

    // Started once and left running while the frames keep coming. isPlaying is
    // asked first because playSoundImpl would start a second copy every frame
    // otherwise, which is the same guard DeviceData puts in front of its own
    // loop sound.
    private static void plzStartMegaphoneStatic(IsoPlayer speaker) {
        if (speaker == null) {
            return;
        }

        Short id = speaker.getOnlineID();
        if (plzMegaphoneLoops.containsKey(id)) {
            return;
        }
        if (speaker.getEmitter() == null || speaker.getEmitter().isPlaying(PLZ_MEGAPHONE_STATIC)) {
            return;
        }

        // playSoundImpl, not playSound: this is a LOCAL sound on the listener's
        // machine. Every client that can hear the voice starts its own copy, and
        // a networked one would have each of them broadcasting the same hiss to
        // all the others.
        long handle = speaker.getEmitter().playSoundImpl(PLZ_MEGAPHONE_STATIC, null);
        if (handle > 0L) {
            plzMegaphoneLoops.put(id, handle);
        }
    }

    // CALLED FOR EVERYBODY, not only for a speaker known to have stopped. The
    // map is the record of what this machine started, so asking it to stop
    // something it never started is free, and it is the only thing that ends a
    // hiss when the speaker simply stops talking.
    private static void plzStopMegaphoneStatic(IsoPlayer speaker) {
        if (speaker == null) {
            return;
        }

        Long handle = plzMegaphoneLoops.remove(speaker.getOnlineID());
        if (handle == null) {
            return;
        }
        if (speaker.getEmitter() != null) {
            speaker.getEmitter().stopOrTriggerSound(handle);
        }
    }

    public static void plzRepublishChannels() {
        if (GameClient.client && GameClient.connection != null) {
            VoiceManager.instance.UpdateChannelsRoaming(GameClient.connection);
        }
    }

    private static boolean plzIsRadioPttDown() {
        String binding = plzRadioPttBinding;
        if (binding == null || binding.isEmpty()) {
            return false;
        }
        return GameKeyboard.isKeyDown(binding);
    }

    private static boolean plzConfigBool(KahluaTable cfg, String key, boolean fallback) {
        Object value = cfg.rawget(key);
        return value instanceof Boolean b ? b : fallback;
    }

    private static float plzConfigFloat(KahluaTable cfg, String key, float fallback) {
        Object value = cfg.rawget(key);
        return value instanceof Double d ? d.floatValue() : fallback;
    }

    // THE CEILING IS THE GAIN, NOT 1.0, and that one number is the whole of what
    // makes the loudness setting reach anything. Vanilla clamped here, so a voice
    // could never play above what the game itself would produce - and what the
    // game itself produces is already below full scale, because the listener's
    // own voice slider is folded in as volumePlayers/12 and its maximum ordinary
    // setting is ten. A gain of 1.0 leaves this byte-identical to vanilla; every
    // other caller passes a value at or below 1.0 and is unaffected, because the
    // slider can only ever bring those down.
    private void setUserPlaySound(long userPlayChannel, float volume) {
        volume = IsoUtils.clamp(
            volume * IsoUtils.lerp(this.volumePlayers, 0.0F, 12.0F), 0.0F, PLZVoice.playbackCeiling()
        );
        javafmod.FMOD_Channel_SetVolume(userPlayChannel, volume);
    }

    private long getUserPlaySound(short onlineId) {
        VoiceManagerData d = VoiceManagerData.get(onlineId);
        if (d.userplaychannel == 0L) {
            d.userplaysound = 0L;
            int mode = is3D ? 1170 : 1154;
            d.userplaysound = javafmod.FMOD_System_CreateRAWPlaySound(mode, 2L, sampleRate);
            if (d.userplaysound == 0L) {
                DebugType.Voice.warn("FMOD_System_CreateSound result=%d", d.userplaysound);
            }

            d.userplaychannel = javafmod.FMOD_System_PlaySound(d.userplaysound, false);
            if (d.userplaychannel == 0L) {
                DebugType.Voice.warn("FMOD_System_PlaySound result=%d", d.userplaychannel);
            }

            javafmod.FMOD_Channel_SetVolume(d.userplaychannel, (float)(this.volumePlayers * 0.2));
            javafmod.FMOD_Channel_SetPriority(d.userplaychannel, 0);
            if (is3D) {
                javafmod.FMOD_Channel_Set3DMinMaxDistance(d.userplaychannel, minDistance / 2.0F, maxDistance);
            }

            javafmod.FMOD_Channel_SetChannelGroup(d.userplaychannel, this.fmodChannelGroup);
        }

        return d.userplaysound;
    }

    public void InitVMClient() {
        if (!Core.soundDisabled && !voipDisabled) {
            int numDevices = javafmod.FMOD_System_GetRecordNumDrivers();
            this.fmodVoiceRecordDriverId = Core.getInstance().getOptionVoiceRecordDevice() - 1;
            if (this.fmodVoiceRecordDriverId < 0 && numDevices > 0) {
                Core.getInstance().setOptionVoiceRecordDevice(1);
                this.fmodVoiceRecordDriverId = Core.getInstance().getOptionVoiceRecordDevice() - 1;
            }

            if (numDevices < 1) {
                DebugType.Voice.debugln("Microphone not found");
                this.initialiseRecDev = false;
            } else if (this.fmodVoiceRecordDriverId < 0 | this.fmodVoiceRecordDriverId >= numDevices) {
                DebugType.Voice.warn("Invalid record device");
                this.initialiseRecDev = false;
            } else {
                this.initialiseRecDev = true;
            }

            this.isEnable = Core.getInstance().getOptionVoiceEnable();
            this.setMode(Core.getInstance().getOptionVoiceMode());
            this.vadMode = Core.getInstance().getOptionVoiceVADMode();
            this.volumeMic = Core.getInstance().getOptionVoiceVolumeMic();
            this.volumePlayers = Core.getInstance().getOptionVoiceVolumePlayers();
            this.fmodChannelGroup = javafmod.FMOD_System_CreateChannelGroup("VOIP");
            this.VoiceInitClient();
            this.fmodRecordSound = 0L;
            if (this.isEnable) {
                this.InitRecDeviceForTest();
            }

            this.timeLast = System.currentTimeMillis();
            this.quit = false;
            this.thread = new Thread() {
                @Override
                public void run() {
                    while (!VoiceManager.this.quit) {
                        try {
                            VoiceManager.this.UpdateVMClient();
                            sleep(VoiceManager.period / 2);
                        } catch (Exception ex) {
                            DebugType.General.printException(ex, LogSeverity.Error);
                        }
                    }
                }
            };
            this.thread.setName("VoiceManagerClient");
            this.thread.start();
        } else {
            this.isEnable = false;
            this.initialiseRecDev = false;
            this.initialisedRecDev = false;
            DebugType.Voice.debugln("Disabled");
        }
    }

    public void loadConfig() {
        this.isEnable = Core.getInstance().getOptionVoiceEnable();
        this.setMode(Core.getInstance().getOptionVoiceMode());
        this.vadMode = Core.getInstance().getOptionVoiceVADMode();
        this.volumeMic = Core.getInstance().getOptionVoiceVolumeMic();
        this.volumePlayers = Core.getInstance().getOptionVoiceVolumePlayers();
    }

    public void UpdateRecordDevice() {
        if (this.initialisedRecDev) {
            this.threadSafeCode(this::UpdateRecordDeviceInternal);
        }
    }

    private void UpdateRecordDeviceInternal() {
        int result = javafmod.FMOD_System_RecordStop(this.fmodVoiceRecordDriverId);
        if (result != FMOD_RESULT.FMOD_OK.ordinal()) {
            DebugType.Voice.warn("FMOD_System_RecordStop result=%d", result);
        }

        this.fmodVoiceRecordDriverId = Core.getInstance().getOptionVoiceRecordDevice() - 1;
        if (this.fmodVoiceRecordDriverId < 0) {
            DebugType.Voice.error("No record device found");
        } else {
            result = javafmod.FMOD_System_RecordStart(this.fmodVoiceRecordDriverId, this.fmodRecordSound, true);
            if (result != FMOD_RESULT.FMOD_OK.ordinal()) {
                DebugType.Voice.warn("FMOD_System_RecordStart result=%d", result);
            }
        }
    }

    public void DeinitVMClient() {
        if (this.thread != null) {
            this.quit = true;
            synchronized (this.notifier) {
                this.notifier.notify();
            }

            while (this.thread.isAlive()) {
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException var4) {
                }
            }

            this.thread = null;
        }

        this.DeinitRecSound();
        ArrayList<VoiceManagerData> data = VoiceManagerData.data;

        for (int i = 0; i < data.size(); i++) {
            VoiceManagerData d = data.get(i);
            if (d.userplaychannel != 0L) {
                javafmod.FMOD_Channel_Stop(d.userplaychannel);
            }

            if (d.userplaysound != 0L) {
                javafmod.FMOD_RAWPlaySound_Release(d.userplaysound);
                d.userplaysound = 0L;
            }
        }

        VoiceManagerData.data.clear();
    }

    public void setTestingMicrophone(boolean testing) {
        if (testing) {
            this.testingMicrophoneMs = System.currentTimeMillis();
        }

        if (testing != this.testingMicrophone) {
            this.testingMicrophone = testing;
            this.notifyThread();
        }
    }

    public void notifyThread() {
        synchronized (this.notifier) {
            this.notifier.notify();
        }
    }

    public void update() {
        if (!GameServer.server) {
            if (this.testingMicrophone) {
                long ms = System.currentTimeMillis();
                if (ms - this.testingMicrophoneMs > 1000L) {
                    this.setTestingMicrophone(false);
                }
            }

            if ((!GameClient.client || GameClient.connection == null) && !FakeClientManager.isVOIPEnabled()) {
                if (this.isClient) {
                    this.isClient = false;
                    this.notifyThread();
                }
            } else if (!this.isClient) {
                this.isClient = true;
                this.notifyThread();
            }
        }
    }

    private float getCanHearAllVolume(float range) {
        return range > minDistance ? IsoUtils.clamp(1.0F - IsoUtils.lerp(range, minDistance, maxDistance), 0.2F, 1.0F) : 1.0F;
    }

    private void threadSafeCode(Runnable runnable) {
        while (true) {
            try {
                this.recDevSemaphore.acquire();
            } catch (InterruptedException var7) {
                continue;
            }

            try {
                runnable.run();
            } finally {
                this.recDevSemaphore.release();
            }

            return;
        }
    }

    synchronized void UpdateVMClient() throws InterruptedException {
        while (!this.quit && !this.isClient && !this.testingMicrophone) {
            synchronized (this.notifier) {
                try {
                    this.notifier.wait();
                } catch (InterruptedException var11) {
                }
            }
        }

        if (serverVOIPEnable) {
            if (IsoPlayer.getInstance() != null) {
                IsoPlayer.getInstance().isSpeek = System.currentTimeMillis() - this.indicatorIsVoice <= 300L;
            }

            if (this.initialiseRecDev) {
                this.recDevSemaphore.acquire();
                javafmod.FMOD_System_GetRecordPosition(this.fmodVoiceRecordDriverId, this.fmodRecordPosition);
                if (fmodReceiveBuffer != null) {
                    while ((this.fmodSoundDataError = javafmod.FMOD_Sound_GetData(this.fmodRecordSound, fmodReceiveBuffer, this.fmodSoundData)) == 0) {
                        // PLZ VOICE CHANGER. Before the frame reaches the encoder, so the changed
                        // voice is what is sent and every listener hears it without carrying the
                        // patch or being told which preset was picked. See PLZVoiceChanger.
                        //
                        // ABOVE THE SEND GATE ON PURPOSE. The shifter carries a ring between
                        // calls; running it only on frames a VAD gate lets through would leave a
                        // seam in that ring at every word boundary. Shift always, send sometimes.
                        PLZVoiceChanger.process(fmodReceiveBuffer, (int)this.fmodSoundData.size);

                        if ((IsoPlayer.getInstance() != null && GameClient.connection != null || FakeClientManager.isVOIPEnabled())
                            && (!is3D || !IsoPlayer.getInstance().isDead())) {
                            if (this.isModePpt) {
                                if (GameKeyboard.isKeyDown("Enable voice transmit") || plzIsRadioPttDown()) {
                                    RakVoice.SendFrame(
                                        GameClient.connection.getConnectedGUID(),
                                        IsoPlayer.getInstance().getOnlineID(),
                                        fmodReceiveBuffer,
                                        this.fmodSoundData.size
                                    );
                                    this.indicatorIsVoice = System.currentTimeMillis();
                                } else if (FakeClientManager.isVOIPEnabled()) {
                                    RakVoice.SendFrame(
                                        FakeClientManager.getConnectedGUID(), FakeClientManager.getOnlineID(), fmodReceiveBuffer, this.fmodSoundData.size
                                    );
                                    this.indicatorIsVoice = System.currentTimeMillis();
                                }
                            }

                            if (this.isModeVad && this.fmodSoundData.vad != 0L) {
                                RakVoice.SendFrame(
                                    GameClient.connection.getConnectedGUID(), IsoPlayer.getInstance().getOnlineID(), fmodReceiveBuffer, this.fmodSoundData.size
                                );
                                this.indicatorIsVoice = System.currentTimeMillis();
                            }
                        }
                    }
                }

                this.recDevSemaphore.release();
            }

            ArrayList<IsoPlayer> players = GameClient.instance.getPlayers();
            ArrayList<VoiceManagerData> data = VoiceManagerData.data;

            for (int i = 0; i < data.size(); i++) {
                VoiceManagerData d = data.get(i);
                boolean online = false;

                for (int pn = 0; pn < players.size(); pn++) {
                    IsoPlayer player = players.get(pn);
                    if (player.onlineId == d.index) {
                        online = true;
                        break;
                    }
                }

                // A peer the world has forgotten but the radio has not counts as online here, or
                // the loop below tears down the very channel plzServiceRadioOnlyPeers is feeding.
                if (!online && this.plzRadioLink(d) != null) {
                    online = true;
                }

                if (false & d.index == 0) {
                    break;
                }

                if (d.userplaychannel != 0L & !online) {
                    javafmod.FMOD_Channel_Stop(d.userplaychannel);
                    d.userplaychannel = 0L;
                }
            }

            long currentTime = System.currentTimeMillis() - this.timeLast;
            if (currentTime >= period) {
                this.timeLast += currentTime;
                if (IsoPlayer.getInstance() == null) {
                    return;
                }

                for (IsoPlayer player : players) {
                    IsoPlayer me = IsoPlayer.getInstance();
                    if (player != me && player.getOnlineID() != -1) {
                        VoiceManagerData d = VoiceManagerData.get(player.getOnlineID());

                        while (RakVoice.ReceiveFrame(player.getOnlineID(), this.buf)) {
                            d.voicetimeout = 10L;
                            plzCountFrame(player.getOnlineID());
                            if (PLZFixes.on(PLZFixes.CHANNEL_PROBE)) {
                                PLZChannelProbe.observe(d.userplaychannel);
                            }
                            if (!d.userplaymute) {
                                float range = IsoUtils.DistanceTo(me.getX(), me.getY(), player.getX(), player.getY());
                                if (me.canHearAll()) {
                                    javafmodJNI.FMOD_Channel_Set3DLevel(d.userplaychannel, 0.0F);
                                    javafmod.FMOD_Channel_Set3DAttributes(d.userplaychannel, me.getX(), me.getY(), me.getZ(), 0.0F, 0.0F, 0.0F);
                                    this.setUserPlaySound(d.userplaychannel, this.getCanHearAllVolume(range));
                                } else {
                                    boolean plzSameVehicle = plzSharesVehicle(me, player);
                                    VoiceManagerData.RadioData rdata = this.checkForNearbyRadios(d);
                                    if (rdata != null && rdata.deviceData != null && !plzSameVehicle) {
                                        javafmodJNI.FMOD_Channel_Set3DLevel(d.userplaychannel, 0.0F);
                                        javafmod.FMOD_Channel_Set3DAttributes(d.userplaychannel, me.getX(), me.getY(), me.getZ(), 0.0F, 0.0F, 0.0F);
                                        this.setUserPlaySound(d.userplaychannel, rdata.deviceData.getDeviceVolume());
                                        rdata.deviceData.doReceiveMPSignal(rdata.lastReceiveDistance);
                                    } else {
                                        if (rdata == null && !plzSameVehicle) {
                                            // The one silent mute in the path. A frame arrived, was
                                            // decoded, and is dropped with nothing said - so say it.
                                            plzLogMute(me, player, d, range);
                                            javafmodJNI.FMOD_Channel_Set3DLevel(d.userplaychannel, 0.0F);
                                            javafmod.FMOD_Channel_Set3DAttributes(d.userplaychannel, me.getX(), me.getY(), me.getZ(), 0.0F, 0.0F, 0.0F);
                                            javafmod.FMOD_Channel_SetVolume(d.userplaychannel, 0.0F);
                                        } else {
                                            if (is3D) {
                                                javafmodJNI.FMOD_Channel_Set3DLevel(d.userplaychannel, IsoUtils.lerp(range, 0.0F, minDistance));
                                                javafmod.FMOD_Channel_Set3DAttributes(
                                                    d.userplaychannel, player.getX(), player.getY(), player.getZ(), 0.0F, 0.0F, 0.0F
                                                );
                                            } else {
                                                javafmodJNI.FMOD_Channel_Set3DLevel(d.userplaychannel, 0.0F);
                                                javafmod.FMOD_Channel_Set3DAttributes(d.userplaychannel, me.getX(), me.getY(), me.getZ(), 0.0F, 0.0F, 0.0F);
                                            }

                                            int speakerMode = plzSpeakerMode(d);
                                            // range, NOT rdata.lastReceiveDistance. The mode comes
                                            // off the routing entry and has to - it is the only
                                            // thing carrying it - but the DISTANCE does not, and
                                            // taking it from there made loudness a staircase: the
                                            // routing entry refreshes every 3010 ms and holds whole
                                            // tiles, so a voice jumped between volume steps instead
                                            // of fading. range is IsoUtils.DistanceTo on live
                                            // positions, computed a few lines above for the
                                            // hear-all branch, and it is what makes the falloff
                                            // smooth AND makes it - rather than the routing gate -
                                            // the thing that decides where a voice stops.
                                            this.setUserPlaySound(
                                                d.userplaychannel,
                                                PLZVoice.volumeFor(
                                                    speakerMode, range, minDistance, maxDistance
                                                )
                                            );

                                            // The hiss rides the PROXIMITY path only. A voice
                                            // coming out of a radio already has the device's own
                                            // static under it, and stacking a second copy on top
                                            // would make a megaphone held next to a walkie sound
                                            // like two radios.
                                            if (PLZVoice.isMegaphone(speakerMode)) {
                                                plzStartMegaphoneStatic(player);
                                            } else {
                                                plzStopMegaphoneStatic(player);
                                            }
                                        }

                                        if (range > maxDistance) {
                                            logFrame(me, player, range);
                                        }
                                    }
                                }

                                javafmod.FMOD_System_RAWPlayData(this.getUserPlaySound(player.getOnlineID()), this.buf, this.buf.length);
                            }
                        }

                        if (d.voicetimeout == 0L) {
                            player.isSpeek = false;
                            // The one place a hiss reliably ends. A speaker who
                            // stops talking, walks out of range, or switches off
                            // the megaphone all arrive here the same way: their
                            // frames stop and the timeout runs out.
                            plzStopMegaphoneStatic(player);
                        } else {
                            d.voicetimeout--;
                            player.isSpeek = true;
                        }
                    }
                }

                this.plzServiceRadioOnlyPeers(players);
            }
        }
    }

    // WHY A RADIO WITH A MAP-WIDE RANGE STILL WENT SILENT ACROSS TOWN, and the whole of what the
    // pass below fixes. The loop above only ever asks RakVoice for frames from players this client
    // still holds an IsoPlayer for, and it stops holding one about five seconds after they leave
    // its chunk-relevance box: the server relays PlayerPacket only to connections isRelevantTo the
    // speaker's position, so GameClient.timeoutRemotePlayers drops them. Nothing about the routing
    // array is wrong at that point - SyncRadioData is relayed to EVERY connection unconditionally,
    // so the freq/range/position needed to decide "same channel, in range" is right here - there is
    // simply nobody listening for the frames. So the walkie worked at the range you could have
    // shouted at, and nowhere else.
    //
    // Nothing here touches an IsoPlayer or the world. The peers this services have no character
    // loaded on this machine by design; propping their objects up to keep them out of the timeout
    // would leave a frozen copy of everybody standing wherever they were last seen.
    private void plzServiceRadioOnlyPeers(ArrayList<IsoPlayer> players) {
        ArrayList<VoiceManagerData> data = VoiceManagerData.data;

        synchronized (plzRadioOnlyPeers) {
            plzRadioOnlyPeers.clear();
        }

        for (int i = 0; i < data.size(); i++) {
            VoiceManagerData d = data.get(i);
            boolean loaded = false;

            for (int pn = 0; pn < players.size(); pn++) {
                if (players.get(pn).onlineId == d.index) {
                    loaded = true;
                    break;
                }
            }

            if (loaded) {
                continue;
            }

            VoiceManagerData.RadioData link = this.plzRadioLink(d);
            if (link == null) {
                continue;
            }

            synchronized (plzRadioOnlyPeers) {
                plzRadioOnlyPeers.add(d.index);
            }

            IsoPlayer me = IsoPlayer.getInstance();

            while (RakVoice.ReceiveFrame(d.index, this.buf)) {
                d.voicetimeout = 10L;
                plzCountFrame(d.index);
                if (d.userplaymute) {
                    continue;
                }

                // Non-positional, at the listening radio's own volume - the same treatment the
                // loop above gives a radio match, because a voice arriving out of a speaker has
                // no direction to come from.
                javafmodJNI.FMOD_Channel_Set3DLevel(d.userplaychannel, 0.0F);
                javafmod.FMOD_Channel_Set3DAttributes(d.userplaychannel, me.getX(), me.getY(), me.getZ(), 0.0F, 0.0F, 0.0F);
                this.setUserPlaySound(d.userplaychannel, link.deviceData.getDeviceVolume());
                link.deviceData.doReceiveMPSignal(link.lastReceiveDistance);
                javafmod.FMOD_System_RAWPlayData(this.getUserPlaySound(d.index), this.buf, this.buf.length);
            }

            if (d.voicetimeout > 0L) {
                d.voicetimeout--;
            }
        }
    }

    // Our own radio entry that matches theirs, or null. checkForNearbyRadios answers with the
    // LISTENER's entry, and only a radio match carries a deviceData: both the hear-all branch and
    // the proximity fallback come back with a null one, and neither has any business reaching
    // somebody far enough away that the world has unloaded them.
    private VoiceManagerData.RadioData plzRadioLink(VoiceManagerData theirs) {
        IsoPlayer me = IsoPlayer.getInstance();
        if (me == null || me.onlineId == -1 || theirs.index == me.onlineId) {
            return null;
        }

        VoiceManagerData.RadioData link = this.checkForNearbyRadios(theirs);
        return link != null && link.deviceData != null ? link : null;
    }

    private static void plzCountFrame(short onlineId) {
        synchronized (plzFrameCounts) {
            Integer seen = plzFrameCounts.get(onlineId);
            plzFrameCounts.put(onlineId, seen == null ? 1 : seen + 1);
        }
    }

    private static long plzMuteLogMs;

    // Names WHICH test killed the frame. Every voice diagnosis so far has been inferred backwards
    // from logs that answer a different question; this one answers it directly.
    private static void plzLogMute(IsoPlayer me, IsoPlayer player, VoiceManagerData speaker, float range) {
        long now = System.currentTimeMillis();
        if (now < plzMuteLogMs) {
            return;
        }

        plzMuteLogMs = now + 3000L;

        String why;
        int myFreq = PLZVoice.entryChannel(me.getZi());
        int theirFreq = Integer.MIN_VALUE;
        float theirRange = -1.0F;

        synchronized (speaker.radioData) {
            if (speaker.radioData.isEmpty()) {
                why = "speaker has published no routing entry yet";
            } else {
                theirFreq = speaker.radioData.get(0).freq;
                theirRange = speaker.radioData.get(0).distance;
                if (!(theirRange > 0.0F)) {
                    why = "speaker publishes range 0 (private call, or no range)";
                } else if (PLZVoice.isFloorsEnabled() && myFreq != theirFreq) {
                    why = "floor mismatch";
                } else {
                    why = "routing distance past the gate";
                }
            }
        }

        DebugType.Multiplayer
            .warn(
                String.format(
                    "PLZ voice MUTED \"%s\" -> \"%s\": %s (live range=%.1f, myFreq=%d, theirFreq=%d, theirRange=%.1f)",
                    player.getUsername(), me.getUsername(), why, range, myFreq, theirFreq, theirRange
                )
            );
    }

    private static void logFrame(IsoPlayer me, IsoPlayer player, float distance) {
        long currentTime = System.currentTimeMillis();
        if (currentTime > timestamp) {
            timestamp = currentTime + 5000L;
            DebugType.Multiplayer
                .warn(
                    String.format(
                        "\"%s\" (%b) received VOIP frame from \"%s\" (%b) at distance=%f",
                        me.getUsername(),
                        me.canHearAll(),
                        player.getUsername(),
                        player.canHearAll(),
                        distance
                    )
                );
        }
    }

    private VoiceManagerData.RadioData checkForNearbyRadios(VoiceManagerData radioData) {
        IsoPlayer me = IsoPlayer.getInstance();
        VoiceManagerData myRadioData = VoiceManagerData.get(me.onlineId);
        if (myRadioData.isCanHearAll) {
            myRadioData.radioData.get(0).lastReceiveDistance = 0.0F;
            return myRadioData.radioData.get(0);
        }

        synchronized (myRadioData.radioData) {
            for (int i = 1; i < myRadioData.radioData.size(); i++) {
                if (PLZVoice.isVoiceChannel(myRadioData.radioData.get(i).freq)) {
                    continue;
                }

                synchronized (radioData.radioData) {
                    for (int j = 1; j < radioData.radioData.size(); j++) {
                        if (PLZVoice.isVoiceChannel(radioData.radioData.get(j).freq)) {
                            continue;
                        }

                        if (myRadioData.radioData.get(i).freq == radioData.radioData.get(j).freq) {
                            float dx = myRadioData.radioData.get(i).x - radioData.radioData.get(j).x;
                            float dy = myRadioData.radioData.get(i).y - radioData.radioData.get(j).y;
                            myRadioData.radioData.get(i).lastReceiveDistance = (float)Math.sqrt(dx * dx + dy * dy);
                            if (myRadioData.radioData.get(i).lastReceiveDistance < radioData.radioData.get(j).distance) {
                                return myRadioData.radioData.get(i);
                            }
                        }
                    }
                }
            }
        }

        synchronized (myRadioData.radioData) {
            synchronized (radioData.radioData) {
                if (!radioData.radioData.isEmpty() && !myRadioData.radioData.isEmpty()) {
                    float dx = myRadioData.radioData.get(0).x - radioData.radioData.get(0).x;
                    float dy = myRadioData.radioData.get(0).y - radioData.radioData.get(0).y;
                    myRadioData.radioData.get(0).lastReceiveDistance = (float)Math.sqrt(dx * dx + dy * dy);
                    // gateRange, NOT audibleRange. Both positions in that subtraction came off a
                    // routing entry that is republished once every 3010 ms and stored in whole
                    // tiles, so at PLZ's eight-tile VoiceMaxDistance the error is a full speech
                    // radius and a tight test here chops a standing conversation into three-second
                    // pieces. The drift allowance costs nothing, because the RANGE is enforced
                    // downstream by PLZVoice.volumeFor on the live distance - see PLZVoice.gateRange.
                    float plzAudible = PLZVoice.gateRange(
                        myRadioData.radioData.get(0).freq,
                        radioData.radioData.get(0).freq,
                        radioData.radioData.get(0).distance,
                        maxDistance
                    );
                    if (myRadioData.radioData.get(0).lastReceiveDistance < plzAudible) {
                        return myRadioData.radioData.get(0);
                    }
                }
            }

            return null;
        }
    }

    public void UpdateChannelsRoaming(UdpConnection connection) {
        IsoPlayer me = IsoPlayer.getInstance();
        if (me.onlineId != -1) {
            VoiceManagerData myRadioData = VoiceManagerData.get(me.onlineId);
            boolean isCanHearAll = false;
            synchronized (myRadioData.radioData) {
                myRadioData.radioData.clear();
                Set<Integer> tmpDeviceIDs = new HashSet<>();

                for (int i = 0; i < IsoPlayer.numPlayers; i++) {
                    IsoPlayer player = IsoPlayer.players[i];
                    if (player != null) {
                        isCanHearAll |= player.canHearAll();
                        float plzMaxDistance = RakVoice.GetMaxDistance();
                        int plzMode = PLZVoice.getMode(player.getIndex());

                        myRadioData.radioData
                            .add(
                                new VoiceManagerData.RadioData(
                                    PLZVoice.entryChannel(player.getZi()),
                                    plzPrivateCall ? 0.0F : PLZVoice.entryRange(plzMode, plzMaxDistance),
                                    player.getX(),
                                    player.getY()
                                )
                            );

                        for (int j = 0; j < player.getInventory().getItems().size(); j++) {
                            InventoryItem item = player.getInventory().getItems().get(j);
                            if (item instanceof Radio radio) {
                                DeviceData deviceData = radio.getDeviceData();
                                if (deviceData != null && deviceData.getIsTurnedOn()) {
                                    myRadioData.radioData.add(new VoiceManagerData.RadioData(deviceData, player.getX(), player.getY()));
                                }
                            }
                        }

                        for (int x = (int)player.getX() - 4; x < player.getX() + 5.0F; x++) {
                            for (int y = (int)player.getY() - 4; y < player.getY() + 5.0F; y++) {
                                for (int z = player.getZi() - 1; z < player.getZi() + 1; z++) {
                                    IsoGridSquare sq = IsoCell.getInstance().getGridSquare(x, y, z);
                                    if (sq != null) {
                                        if (sq.getObjects() != null) {
                                            for (int j = 0; j < sq.getObjects().size(); j++) {
                                                IsoObject item = sq.getObjects().get(j);
                                                if (item instanceof IsoRadio isoRadio) {
                                                    DeviceData deviceData = isoRadio.getDeviceData();
                                                    if (deviceData != null && deviceData.getIsTurnedOn()) {
                                                        myRadioData.radioData.add(new VoiceManagerData.RadioData(deviceData, sq.x, sq.y));
                                                        if (!item.getModData().isEmpty()) {
                                                            Object id = item.getModData().rawget("RadioItemID");
                                                            if (id != null && id instanceof Double d) {
                                                                tmpDeviceIDs.add(d.intValue());
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (sq.getWorldObjects() != null) {
                                            for (int j = 0; j < sq.getWorldObjects().size(); j++) {
                                                IsoWorldInventoryObject item = sq.getWorldObjects().get(j);
                                                if (item.getItem() != null && item.getItem() instanceof Radio && !tmpDeviceIDs.contains(item.getItem().getID())
                                                    )
                                                 {
                                                    DeviceData deviceData = ((Radio)item.getItem()).getDeviceData();
                                                    if (deviceData != null && deviceData.getIsTurnedOn()) {
                                                        myRadioData.radioData.add(new VoiceManagerData.RadioData(deviceData, sq.x, sq.y));
                                                    }
                                                }
                                            }
                                        }

                                        if (sq.getVehicleContainer() != null && sq == sq.getVehicleContainer().getSquare()) {
                                            VehiclePart part = sq.getVehicleContainer().getPartById("Radio");
                                            if (part != null) {
                                                DeviceData deviceData = part.getDeviceData();
                                                if (deviceData != null && deviceData.getIsTurnedOn()) {
                                                    myRadioData.radioData.add(new VoiceManagerData.RadioData(deviceData, sq.x, sq.y));
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            ByteBufferWriter b = connection.startPacket();
            PacketTypes.PacketType.SyncRadioData.doPacket(b);
            b.putBoolean(isCanHearAll);
            b.putInt(myRadioData.radioData.size() * 4);

            for (VoiceManagerData.RadioData data : myRadioData.radioData) {
                b.putInt(data.freq);
                b.putInt((int)data.distance);
                b.putInt(data.x);
                b.putInt(data.y);
            }

            PacketTypes.PacketType.SyncRadioData.send(connection);
        }
    }

    void InitVMServer() {
        this.VoiceInitServer(
            ServerOptions.instance.voiceEnable.getValue(),
            24000,
            20,
            5,
            PLZVoice.bufferingBytes(),
            ServerOptions.instance.voiceMinDistance.getValue(),
            ServerOptions.instance.voiceMaxDistance.getValue(),
            ServerOptions.instance.voice3d.getValue()
        );
    }

    public int getMicVolumeIndicator() {
        return fmodReceiveBuffer == null ? 0 : (int)this.fmodSoundData.loudness;
    }

    public boolean getMicVolumeError() {
        return fmodReceiveBuffer == null ? true : this.fmodSoundDataError == -1;
    }

    public boolean getServerVOIPEnable() {
        return serverVOIPEnable;
    }

    public void VMServerBan(short playerId, boolean isBan) {
        RakVoice.SetVoiceBan(playerId, isBan);
    }
}
