package com.ishland.fabric.raisesoundlimit.sound;

import com.google.common.base.Suppliers;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.ishland.fabric.raisesoundlimit.FabricLoader;
import com.ishland.fabric.raisesoundlimit.MixinUtils;
import com.ishland.fabric.raisesoundlimit.internal.SoundHandleCreationFailedException;
import com.ishland.fabric.raisesoundlimit.internal.SoundSystemPriorityObjectPool;
import com.ishland.fabric.raisesoundlimit.mixininterface.ISoundEngine;
import com.ishland.fabric.raisesoundlimit.mixininterface.ISoundSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ChatHud;
import net.minecraft.client.options.GameOptions;
import net.minecraft.client.render.Camera;
import net.minecraft.client.sound.*;
import net.minecraft.resource.ResourceManager;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Identifier;
import org.apache.commons.pool2.PooledObject;
import org.apache.commons.pool2.impl.AbandonedConfig;
import org.apache.commons.pool2.impl.GenericObjectPool;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class PooledSoundSystem extends SoundSystem {

    private static final Supplier<Field> allSoundSystemField = Suppliers.memoize(() -> {
        Field field;
        try {
            field = GenericObjectPool.class.getDeclaredField("allObjects");
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        field.setAccessible(true);
        return field;
    });
    private static final String calculatingDebugString = "Calculating...";

    // Executors and pools
    private final GenericObjectPool<SoundSystem> pool;
    private final Set<Thread> internalExecutorThreads = Sets.newConcurrentHashSet();
    private final ThreadPoolExecutor internalExecutor =
            (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.max(Runtime.getRuntime().availableProcessors(), 1),
                    new ThreadFactory() {
                        private final AtomicLong serial = new AtomicLong(0);

                        @Override
                        public Thread newThread(Runnable runnable) {
                            final Thread thread = new Thread(runnable);
                            thread.setName("RSLExec-" + serial.incrementAndGet());
                            thread.setPriority(Thread.NORM_PRIORITY - 1);
                            internalExecutorThreads.add(thread);
                            return thread;
                        }
                    });
    private final ExecutorService constructingExecutor = Executors.newSingleThreadExecutor(
            new ThreadFactoryBuilder().setPriority(Thread.NORM_PRIORITY - 1).setDaemon(true).build());
    final ScheduledExecutorService internalScheduledExecutor =
            Executors.newSingleThreadScheduledExecutor(
                    new ThreadFactory() {
                        private final AtomicLong serial = new AtomicLong(0);

                        @Override
                        public Thread newThread(Runnable runnable) {
                            final Thread thread = new Thread(runnable);
                            thread.setName("RSLSExec-"
                                    + serial.incrementAndGet());
                            thread.setPriority(Thread.NORM_PRIORITY - 1);
                            internalExecutorThreads.add(thread);
                            return thread;
                        }
                    });

    private long lastFetchTime = 0L;
    private String debugString = calculatingDebugString;
    private final AtomicBoolean isResourceLoaded = new AtomicBoolean(false);
    private final AtomicBoolean nextBL = new AtomicBoolean(false);
    private final AtomicReference<CompletableFuture<Void>> lastEviction = new AtomicReference<>(CompletableFuture.completedFuture(null));

    final List<SoundInstanceListener> listeners = Lists.newArrayList();
    final List<TickableSoundInstance> soundsToPlayNextTick = Lists.newArrayList();
    final Map<SoundInstance, Long> startTicks = new ConcurrentHashMap<>();

    private final AtomicLong lastTickStart = new AtomicLong(-1L);
    private final AtomicLong lastTickTime = new AtomicLong(-1L);
    private final AtomicReference<String> tickingStage = new AtomicReference<>("");

    final AtomicLong ticks = new AtomicLong(0L);
    final AtomicLong lastShrinkTicks = new AtomicLong(0L);

    public PooledSoundSystem(SoundManager loader, GameOptions settings, ResourceManager resourceManager) throws Exception {
        super(loader, settings, resourceManager);
        MixinUtils.logger.info("Removing flags for SoundSystem initialization prevention");
        MixinUtils.suppressSoundSystemInit = false;
        // Constructor arguments for SoundSystem
        this.pool = new SoundSystemPriorityObjectPool(new SoundSystemFactory(loader, settings, resourceManager, this));
        pool.setMinIdle(Runtime.getRuntime().availableProcessors());
        pool.setMaxIdle(Runtime.getRuntime().availableProcessors() * 16);
        pool.setMaxTotal(Runtime.getRuntime().availableProcessors() * 16);
        pool.setLifo(false);
        pool.setTestOnBorrow(true);
        pool.setTestOnCreate(true);
        pool.setTestOnReturn(true);
        final AbandonedConfig abandonedConfig = new AbandonedConfig();
        abandonedConfig.setLogAbandoned(true);
        abandonedConfig.setRemoveAbandonedOnMaintenance(true);
        abandonedConfig.setRemoveAbandonedTimeout(3);
        pool.setAbandonedConfig(abandonedConfig);
        internalScheduledExecutor.scheduleAtFixedRate(
                () -> {
                    try {
                        tick0();
                    } catch (Throwable t) {
                        t.printStackTrace();
                    }
                },
                50,
                50,
                TimeUnit.MILLISECONDS
        );
    }

    public void tryExtendSize() {
        constructingExecutor.execute(() -> {
            if (getPoolTotal() < pool.getMaxTotal()) {
                FabricLoader.logger.info("Extending size of sound system");
                MinecraftClient.getInstance().execute(() ->
                        MinecraftClient.getInstance().inGameHud.setOverlayMessage(
                                new LiteralText("Extending size of sound system"),
                                false
                        ));
                try {
                    pool.addObject();
                } catch (Exception e) {
                    final ChatHud chatHud = MinecraftClient.getInstance().inGameHud.getChatHud();
                    chatHud.addMessage(new LiteralText("Unable to extend sound system: "));
                    chatHud.addMessage(new LiteralText(e.toString()));
                    synchronized (lastShrinkTicks) {
                        if (lastShrinkTicks.get() + 30 * 20 < ticks.get()) {
                            lastShrinkTicks.set(ticks.get());
                            this.pool.setMaxTotal(Math.min(1, getPoolTotal() * 2 / 3));
                            this.pool.setMaxIdle(Math.min(1, getPoolTotal() * 2 / 3));
                            this.pool.clear();
                        }
                    }
                }
            }
        });
    }

    private int getPoolTotal() {
        return this.pool.getNumIdle() + pool.getNumActive();
    }

    @Override
    public void reloadSounds() {
        internalExecutor.execute(() -> {
            pool.clear();
            pool.setMinIdle(Runtime.getRuntime().availableProcessors());
            pool.setMaxIdle(Runtime.getRuntime().availableProcessors() * 16);
            pool.setMaxTotal(Runtime.getRuntime().availableProcessors() * 16);
            isResourceLoaded.set(true);
        });
    }

    @Override
    public void updateSoundVolume(SoundCategory soundCategory, float volume) {
        soundSystemForEach(soundSystem -> soundSystem.updateSoundVolume(soundCategory, volume), true);
    }

    @Override
    public void stop() {
        ((SoundSystemFactory) pool.getFactory()).isShuttingDown = true;
        pool.close();
        internalExecutor.shutdown();
        internalScheduledExecutor.shutdown();
        while (!internalExecutor.isTerminated() || !internalScheduledExecutor.isTerminated()) {
            try {
                internalExecutor.awaitTermination(1, TimeUnit.SECONDS);
                internalScheduledExecutor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
            }
        }
    }

    @Override
    public void stop(SoundInstance soundInstance) {
        soundSystemForEach(soundSystem -> soundSystem.stop(soundInstance), false);
    }

    @Override
    public void stopAll() {
        soundSystemForEach(SoundSystem::stopAll, false);
    }

    @Override
    public void registerListener(SoundInstanceListener soundInstanceListener) {
        this.listeners.add(soundInstanceListener);
        soundSystemForEach(soundSystem -> soundSystem.registerListener(soundInstanceListener), true);
    }

    @Override
    public void unregisterListener(SoundInstanceListener soundInstanceListener) {
        this.listeners.remove(soundInstanceListener);
        soundSystemForEach(soundSystem -> soundSystem.unregisterListener(soundInstanceListener), true);
    }

    @Override
    public void tick(boolean bl) {
        if (!bl)
            nextBL.set(false);
    }

    private synchronized void tick0() {
        try {
            lastTickStart.set(System.nanoTime());
            tickingStage.set("initial");
            boolean bl = nextBL.get();
            nextBL.set(true);
            if (!isResourceLoaded.get()) return;
            ticks.incrementAndGet();
            try {
                if (lastEviction.get().isDone())
                    lastEviction.set(CompletableFuture.runAsync(() -> {
                        try {
                            pool.evict();
                        } catch (Exception ignored) {
                        }
                        try {
                            pool.preparePool();
                        } catch (Exception ignored) {
                        }
                    }, constructingExecutor));
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (!bl) {
                //noinspection SpellCheckingInspection
                tickingStage.set("schedsound");
                for (Iterator<TickableSoundInstance> iterator = soundsToPlayNextTick.iterator(); iterator.hasNext(); ) {
                    TickableSoundInstance soundInstance = iterator.next();
                    play(soundInstance);
                    iterator.remove();
                }
                Iterator<Map.Entry<SoundInstance, Long>> iterator = this.startTicks.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<SoundInstance, Long> entry = iterator.next();
                    if (this.ticks.get() >= entry.getValue()) {
                        SoundInstance sound = entry.getKey();
                        if (sound instanceof TickableSoundInstance)
                            MinecraftClient.getInstance().execute(((TickableSoundInstance) sound)::tick);
                        this.play(sound);
                        iterator.remove();
                    }
                }
            }
            //noinspection SpellCheckingInspection
            tickingStage.set("subsystick");
            soundSystemForEach(soundSystem -> soundSystem.tick(bl), false);
        } catch (Throwable t) {
            final ChatHud chatHud = MinecraftClient.getInstance().inGameHud.getChatHud();
            chatHud.addMessage(new LiteralText("Error ticking SoundSystem: " + t.toString()));
            chatHud.addMessage(new LiteralText("Current stage: " + tickingStage.get()));
            throw t;
        } finally {
            tickingStage.set("");
            lastTickTime.set(System.nanoTime() - lastTickStart.get());
        }
    }

    @Override
    public boolean isPlaying(SoundInstance soundInstance) {
        AtomicBoolean isPlaying = new AtomicBoolean(false);
        soundSystemForEach(soundSystem -> {
            if (soundSystem.isPlaying(soundInstance)) {
                isPlaying.set(true);
                throw new BreakException();
            }
        }, false);
        return isPlaying.get();
    }

    @Override
    public void play(SoundInstance soundInstance) {
        internalExecutor.execute(() -> {
            for (int i = 0; i < this.pool.getNumIdle(); i++)
                try {
                    play0(soundInstance);
                    break;
                } catch (SoundHandleCreationFailedException ignored) {
                }
        });
    }

    private void play0(SoundInstance soundInstance) {
        final SoundSystem soundSystem;
        try {
            soundSystem = pool.borrowObject();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            soundSystem.play(soundInstance);
        } catch (SoundHandleCreationFailedException e) {
            throw e;
        } catch (Throwable t) {
            try {
                pool.invalidateObject(soundSystem);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } finally {
            pool.returnObject(soundSystem);
        }
    }

    @Override
    public void playNextTick(TickableSoundInstance sound) {
        this.soundsToPlayNextTick.add(sound);
    }

    @Override
    public void addPreloadedSound(Sound sound) {
        soundSystemForEach(soundSystem -> soundSystem.addPreloadedSound(sound), true);
    }

    @Override
    public void pauseAll() {
        soundSystemForEach(SoundSystem::pauseAll, false);
    }

    @Override
    public void resumeAll() {
        soundSystemForEach(SoundSystem::resumeAll, false);
    }

    @Override
    public void play(SoundInstance sound, int delay) {
        this.startTicks.put(sound, this.ticks.get() + delay);
    }

    @Override
    public void updateListenerPosition(Camera camera) {
        soundSystemForEach(soundSystem -> soundSystem.updateListenerPosition(camera), true);
    }

    @Override
    public void stopSounds(Identifier identifier, SoundCategory soundCategory) {
        soundSystemForEach(soundSystem -> soundSystem.stopSounds(identifier, soundCategory), true);
    }

    @Override
    public String getDebugString() {
        if (Math.abs(lastFetchTime - System.currentTimeMillis()) > 10 * 1000)
            debugString = calculatingDebugString;
        internalExecutor.execute(() -> {
            List<List<SourceSetUsage>> list = new LinkedList<>();
            soundSystemForEach(soundSystem ->
                    list.add(((ISoundEngine) ((ISoundSystem) soundSystem).getSoundEngine()).getUsages()), false);
            int[] used = new int[2];
            int[] max = new int[2];
            list.forEach(sourceSetUsages -> {
                for (int i = 0, sourceSetUsagesLength = sourceSetUsages.size(); i < sourceSetUsagesLength && i < 2; i++) {
                    SourceSetUsage usage = sourceSetUsages.get(i);
                    used[i] += usage.getUsed();
                    max[i] += usage.getMax();
                }
            });
            StringBuilder builder = new StringBuilder();
            for (int i = 0, length = used.length; i < length; i++) {
                builder.append(used[i]).append("/").append(max[i]);
                if (i + 1 < length)
                    builder.append(" + ");
            }
            debugString = builder.toString();
        });
        lastFetchTime = System.currentTimeMillis();
        return "Sound: " + debugString; // For performance, reflection is expensive
    }

    public List<String> getRightDebugString() {
        List<String> list = new LinkedList<>();
        list.add(String.format("SoundSystem pool size: %d/%d/%d",
                pool.getNumActive(), getPoolTotal(), pool.getMaxTotal()));
        list.add(String.format("SoundSystem executor size: %d/%d/%d",
                internalExecutor.getActiveCount(), internalExecutor.getPoolSize(), internalExecutor.getMaximumPoolSize()));
        list.add(String.format("SoundSystem last tick time: %.3fms", lastTickTime.get() / 1000.0 / 1000.0));
        long tickingDuration = tickingStage.get().isEmpty() ? lastTickTime.get() : System.nanoTime() - lastTickStart.get();
        list.add("SoundSystem ticker status: ");
        list.add(String.format("Ticking stage: %10s", tickingStage.get().isEmpty() ? "Idle" : tickingStage.get()));
        list.add(String.format("Ticking duration: %.3fms", tickingDuration / 1000.0 / 1000.0));
        return list;
    }

    private void soundSystemForEach(Consumer<SoundSystem> consumer, boolean useExecutor) {
        final Collection<PooledObject<SoundSystem>> systems = getPooledSystems();
        try {
            for (PooledObject<SoundSystem> pooledSystem : systems) {
                if (useExecutor)
                    internalExecutor.execute(() -> {
                        try {
                            consumer.accept(pooledSystem.getObject());
                        } catch (Throwable t) {
                            try {
                                this.pool.invalidateObject(pooledSystem.getObject());
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });
                else try {
                    consumer.accept(pooledSystem.getObject());
                } catch (BreakException breakException) {
                    throw breakException;
                } catch (Throwable t) {
                    try {
                        this.pool.invalidateObject(pooledSystem.getObject());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (BreakException ignored) {
        }
    }

    private Collection<PooledObject<SoundSystem>> getPooledSystems() {
        final Collection<PooledObject<SoundSystem>> systems;
        try {
            //noinspection unchecked
            systems = new LinkedList<>(
                    ((Map<?, PooledObject<SoundSystem>>) allSoundSystemField.get().get(pool)).values());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
        return systems;
    }

    private static final class BreakException extends RuntimeException {

    }

}
