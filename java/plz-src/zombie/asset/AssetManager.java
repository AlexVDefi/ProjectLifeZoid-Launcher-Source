// Decompiled with Zomboid Decompiler v0.3.2 using Vineflower.
package zombie.asset;

import gnu.trove.map.hash.THashMap;
import java.util.ArrayList;
import zombie.debug.DebugType;
import zombie.fileSystem.IFile;

public abstract class AssetManager implements AssetStateObserver {
    private final AssetManager.AssetTable assets = new AssetManager.AssetTable();
    private AssetManagers owner;
    private boolean isUnloadEnabled;

    /**
     * PLZ: per-manager monitor for every method that touches the private assets table.
     *
     * The table is a plain THashMap and load(AssetPath, AssetParams) is get-then-put. Concurrent
     * puts from the SPVThread (animal-in-vehicle load) and the main thread (boot refreshAnimSets)
     * are what blew up inside THashMap.rehash, and the vehicle-load error handler responded by
     * deleting the vehicle's vehicles.db row.
     *
     * An instance lock, so each manager (AnimNodeAssetManager, PhysicsShapeAssetManager, ...)
     * locks independently. A private lock rather than marking the methods synchronized, because
     * the fidelity gate compares javap output including modifiers.
     *
     * Largely defence in depth now that animSetLock covers the path that actually crashed: these
     * run at asset-load frequency, never per tick. Direct reads via getAssetTable() bypass this,
     * as they do in vanilla.
     */
    private final Object plzAssetsLock = new Object();

    public void create(AssetType type, AssetManagers owner) {
        owner.add(type, this);
        this.owner = owner;
    }

    public void destroy() {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ASSET_MANAGER_SYNC)) {
            synchronized (this.plzAssetsLock) {
                zombie.plz.PLZFixes.hit(zombie.plz.PLZFixes.ASSET_MANAGER_SYNC);
                this.plzDestroy();
                return;
            }
        }

        this.plzDestroy();
    }

    private void plzDestroy() {
        this.assets.forEachValue(asset -> {
            if (!asset.isEmpty()) {
                DebugType.Asset.println("Leaking asset %s", asset.getPath());
            }

            this.destroyAsset(asset);
            return true;
        });
    }

    public void removeUnreferenced() {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ASSET_MANAGER_SYNC)) {
            synchronized (this.plzAssetsLock) {
                this.plzRemoveUnreferenced();
                return;
            }
        }

        this.plzRemoveUnreferenced();
    }

    private void plzRemoveUnreferenced() {
        if (this.isUnloadEnabled) {
            ArrayList<Asset> toRemove = new ArrayList<>();
            this.assets.forEachValue(assetx -> {
                if (assetx.getRefCount() == 0) {
                    toRemove.add(assetx);
                }

                return true;
            });

            for (Asset asset : toRemove) {
                this.assets.remove(asset.getPath());
                this.destroyAsset(asset);
            }
        }
    }

    public Asset load(AssetPath path) {
        return this.load(path, null);
    }

    public Asset load(AssetPath path, AssetManager.AssetParams params) {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ASSET_MANAGER_SYNC)) {
            synchronized (this.plzAssetsLock) {
                return this.plzLoad(path, params);
            }
        }

        return this.plzLoad(path, params);
    }

    private Asset plzLoad(AssetPath path, AssetManager.AssetParams params) {
        if (!path.isValid()) {
            return null;
        }

        Asset asset = this.get(path);
        if (asset == null) {
            asset = this.createAsset(path, params);
            this.assets.put(path.getPath(), asset);
        }

        if (asset.isEmpty() && asset.priv.desiredState == Asset.State.EMPTY) {
            this.doLoad(asset, params);
        }

        asset.addRef();
        return asset;
    }

    public void load(Asset asset) {
        if (asset.isEmpty() && asset.priv.desiredState == Asset.State.EMPTY) {
            this.doLoad(asset, null);
        }

        asset.addRef();
    }

    public void unload(AssetPath path) {
        Asset asset = this.get(path);
        if (asset != null) {
            this.unload(asset);
        }
    }

    public void unload(Asset asset) {
        int newRefCount = asset.rmRef();
        assert newRefCount >= 0;
        if (newRefCount == 0 && this.isUnloadEnabled) {
            this.doUnload(asset);
        }
    }

    public void unloadWithoutDeref(Asset asset) {
        this.doUnload(asset);
    }

    public void onDataReloaded(Asset asset) {
        asset.priv.desiredState = Asset.State.READY;
        this.onLoadingSucceeded(asset);
    }

    public void reload(AssetPath path) {
        Asset asset = this.get(path);
        if (asset != null) {
            this.reload(asset);
        }
    }

    public void reload(Asset asset) {
        this.reload(asset, null);
    }

    public void reload(Asset asset, AssetManager.AssetParams params) {
        this.doUnload(asset);
        this.doLoad(asset, params);
    }

    public void enableUnload(boolean enable) {
        if (zombie.plz.PLZFixes.on(zombie.plz.PLZFixes.ASSET_MANAGER_SYNC)) {
            synchronized (this.plzAssetsLock) {
                this.plzEnableUnload(enable);
                return;
            }
        }

        this.plzEnableUnload(enable);
    }

    private void plzEnableUnload(boolean enable) {
        this.isUnloadEnabled = enable;
        if (enable) {
            this.assets.forEachValue(asset -> {
                if (asset.getRefCount() == 0) {
                    this.doUnload(asset);
                }

                return true;
            });
        }
    }

    private void doLoad(Asset asset, AssetManager.AssetParams params) {
        if (asset.priv.desiredState != Asset.State.READY) {
            asset.priv.desiredState = Asset.State.READY;
            asset.setAssetParams(params);
            this.startLoading(asset);
        }
    }

    private void doUnload(Asset asset) {
        if (asset.priv.task != null) {
            asset.priv.task.cancel();
            asset.priv.task = null;
        }

        asset.priv.desiredState = Asset.State.EMPTY;
        this.unloadData(asset);
        assert asset.priv.emptyDepCount <= 1;
        asset.priv.emptyDepCount = 1;
        asset.priv.failedDepCount = 0;
        asset.priv.checkState();
    }

    @Override
    public void onStateChanged(Asset.State oldState, Asset.State newState, Asset asset) {
    }

    protected void startLoading(Asset asset) {
        if (asset.priv.task == null) {
            asset.priv.task = new AssetTask_LoadFromFileAsync(asset, false);
            asset.priv.task.execute();
        }
    }

    protected final void onLoadingSucceeded(Asset asset) {
        asset.priv.onLoadingSucceeded();
    }

    protected final void onLoadingFailed(Asset asset) {
        asset.priv.onLoadingFailed();
    }

    protected final void setTask(Asset asset, AssetTask task) {
        if (asset.priv.task != null) {
            if (task == null) {
                asset.priv.task = null;
            }
        } else {
            asset.priv.task = task;
        }
    }

    protected boolean loadDataFromFile(Asset asset, IFile file) {
        throw new RuntimeException("not implemented");
    }

    protected void unloadData(Asset asset) {
    }

    public AssetManager.AssetTable getAssetTable() {
        return this.assets;
    }

    public AssetManagers getOwner() {
        return this.owner;
    }

    protected abstract Asset createAsset(AssetPath arg0, AssetManager.AssetParams arg1);

    protected abstract void destroyAsset(Asset arg0);

    protected Asset get(AssetPath path) {
        return this.assets.get(path.getPath());
    }

    public static class AssetParams {
    }

    public static final class AssetTable extends THashMap<String, Asset> {
    }
}
