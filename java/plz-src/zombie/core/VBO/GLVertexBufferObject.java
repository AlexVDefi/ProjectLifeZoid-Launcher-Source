package zombie.core.VBO;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import org.lwjgl.opengl.ARBMapBufferRange;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL44;
import org.lwjglx.opengl.OpenGLException;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.debug.DebugType;
import zombie.plz.PLZPerf;

public class GLVertexBufferObject {
    public static IGLBufferObject funcs;
    private long size;
    private final int type;
    private final int usage;
    private transient int id;
    private transient boolean mapped;
    private transient boolean cleared;
    private transient ByteBuffer buffer;
    private int vertexAttribArray = -1;

    private static final int PLZ_STORAGE_FLAGS = GL30.GL_MAP_WRITE_BIT | GL44.GL_MAP_PERSISTENT_BIT | GL44.GL_MAP_COHERENT_BIT;

    // Buffers whose draw has been submitted but which carry no fence yet. The fence is placed at
    // the next map of ANY buffer, so a run of unmaps inside one frame costs one sync point.
    private static final ArrayList<GLVertexBufferObject> plzAwaitingFence = new ArrayList<>();
    private static boolean plzAnnounced;

    private boolean plzPersistent;
    private long plzFence;

    public static void init() {
        if (GL.getCapabilities().OpenGL15) {
            System.out.println("OpenGL 1.5 buffer objects supported");
            funcs = new GLBufferObject15();
        } else {
            if (!GL.getCapabilities().GL_ARB_vertex_buffer_object) {
                throw new RuntimeException("Neither OpenGL 1.5 nor GL_ARB_vertex_buffer_object supported");
            }

            System.out.println("GL_ARB_vertex_buffer_object supported");
            funcs = new GLBufferObjectARB();
        }

        VertexBufferObject.funcs = funcs;
    }

    public GLVertexBufferObject(long size, int type, int usage) {
        this.size = size;
        this.type = type;
        this.usage = usage;
    }

    public GLVertexBufferObject(int type, int usage) {
        this.size = 0L;
        this.type = type;
        this.usage = usage;
    }

    public void create() {
        this.id = funcs.glGenBuffers();
    }

    public void clear() {
        // Immutable storage cannot be reallocated, and it never needs to be: the mapping is
        // permanent, so the orphan-per-batch this call exists for has nothing left to do.
        if (!this.plzPersistent && !this.cleared) {
            funcs.glBufferData(this.type, this.size, this.usage);
            this.cleared = true;
        }
    }

    private static boolean plzUsePersistent() {
        return PLZPerf.PERSISTENT_VBO && GL.getCapabilities().OpenGL32 && GL.getCapabilities().GL_ARB_buffer_storage;
    }

    private static void plzFencePending() {
        for (int i = 0; i < plzAwaitingFence.size(); i++) {
            GLVertexBufferObject vbo = plzAwaitingFence.get(i);
            if (vbo.plzFence != 0L) {
                GL32.glDeleteSync(vbo.plzFence);
            }

            vbo.plzFence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        }

        plzAwaitingFence.clear();
    }

    /** The whole win: allocate and map once, then hand the same mapping back on every map(). */
    private ByteBuffer plzMapPersistent() {
        plzFencePending();
        if (this.buffer == null) {
            funcs.glBindBuffer(this.type, this.id);
            GL44.glBufferStorage(this.type, this.size, PLZ_STORAGE_FLAGS);
            this.buffer = GL30.glMapBufferRange(this.type, 0L, this.size, PLZ_STORAGE_FLAGS, null);
            if (this.buffer == null) {
                throw new OpenGLException("Failed to persistently map a buffer " + this.size + " bytes long");
            }

            this.plzPersistent = true;
            this.cleared = true;
            if (!plzAnnounced) {
                plzAnnounced = true;
                DebugType.Mod.println("PLZ: persistent VBO mapping active (GL_ARB_buffer_storage)");
            }
        } else if (this.plzFence != 0L) {
            int result = GL32.glClientWaitSync(this.plzFence, GL32.GL_SYNC_FLUSH_COMMANDS_BIT, 1000000000L);
            GL32.glDeleteSync(this.plzFence);
            this.plzFence = 0L;
            if (result == GL32.GL_TIMEOUT_EXPIRED || result == GL32.GL_WAIT_FAILED) {
                DebugType.Mod.warn("PLZ: persistent VBO fence wait returned 0x" + Integer.toHexString(result));
            }
        }

        this.buffer.order(ByteOrder.nativeOrder()).clear().limit((int)this.size);
        this.mapped = true;
        return this.buffer;
    }

    protected void doDestroy() {
        if (this.id != 0) {
            this.unmap();
            if (this.plzPersistent) {
                funcs.glBindBuffer(this.type, this.id);
                funcs.glUnmapBuffer(this.type);
                if (this.plzFence != 0L) {
                    GL32.glDeleteSync(this.plzFence);
                    this.plzFence = 0L;
                }

                this.plzPersistent = false;
                this.buffer = null;
                plzAwaitingFence.remove(this);
            }

            funcs.glDeleteBuffers(this.id);
            this.id = 0;
        }
    }

    public ByteBuffer map(int size) {
        if (!this.mapped) {
            if (this.size != size) {
                this.size = size;
                this.clear();
            }

            if (this.buffer != null && this.buffer.capacity() < size) {
                this.buffer = null;
            }

            ByteBuffer old = this.buffer;
            if (GL.getCapabilities().OpenGL30) {
                int flags = 38;
                this.buffer = GL30.glMapBufferRange(this.type, 0L, size, 38, this.buffer);
            } else if (GL.getCapabilities().GL_ARB_map_buffer_range) {
                int flags = 38;
                this.buffer = ARBMapBufferRange.glMapBufferRange(this.type, 0L, size, 38, this.buffer);
            } else {
                this.buffer = funcs.glMapBuffer(this.type, funcs.GL_WRITE_ONLY(), size, this.buffer);
            }

            if (this.buffer == null) {
                throw new OpenGLException("Failed to map buffer " + this);
            }

            if (this.buffer != old && old != null) {
            }

            this.buffer.order(ByteOrder.nativeOrder()).clear().limit(size);
            this.mapped = true;
            this.cleared = false;
        }

        return this.buffer;
    }

    public ByteBuffer map() {
        if (!this.mapped) {
            assert this.size > 0L;
            if (this.plzPersistent || this.buffer == null && plzUsePersistent()) {
                return this.plzMapPersistent();
            }

            this.clear();
            ByteBuffer old = this.buffer;
            if (GL.getCapabilities().OpenGL30) {
                int flags = 38;
                this.buffer = GL30.glMapBufferRange(this.type, 0L, this.size, 38, this.buffer);
            } else if (GL.getCapabilities().GL_ARB_map_buffer_range) {
                int flags = 38;
                this.buffer = ARBMapBufferRange.glMapBufferRange(this.type, 0L, this.size, 38, this.buffer);
            } else {
                this.buffer = funcs.glMapBuffer(this.type, funcs.GL_WRITE_ONLY(), this.size, this.buffer);
            }

            if (this.buffer == null) {
                throw new OpenGLException("Failed to map a buffer " + this.size + " bytes long");
            }

            if (this.buffer != old && old != null) {
            }

            this.buffer.order(ByteOrder.nativeOrder()).clear().limit((int)this.size);
            this.mapped = true;
            this.cleared = false;
        }

        return this.buffer;
    }

    public void orphan() {
        funcs.glMapBuffer(this.type, this.usage, this.size, null);
    }

    public boolean unmap() {
        if (this.mapped) {
            this.mapped = false;
            if (this.plzPersistent) {
                plzAwaitingFence.add(this);
                return true;
            }

            return funcs.glUnmapBuffer(this.type);
        } else {
            return true;
        }
    }

    public boolean isMapped() {
        return this.mapped;
    }

    public void bufferData(ByteBuffer data) {
        funcs.glBufferData(this.type, data, this.usage);
    }

    @Override
    public String toString() {
        return "GLVertexBufferObject[" + this.id + ", " + this.size + "]";
    }

    public void bind() {
        funcs.glBindBuffer(this.type, this.id);
    }

    public void bindNone() {
        funcs.glBindBuffer(this.type, 0);
    }

    public int getID() {
        return this.id;
    }

    public void enableVertexAttribArray(int index) {
        if (this.vertexAttribArray != index) {
            this.disableVertexAttribArray();
            if (index >= 0) {
                GL20.glEnableVertexAttribArray(index);
            }

            this.vertexAttribArray = index >= 0 ? index : -1;
        }
    }

    public void disableVertexAttribArray() {
        if (this.vertexAttribArray != -1) {
            GL20.glDisableVertexAttribArray(this.vertexAttribArray);
            this.vertexAttribArray = -1;
        }
    }
}
