package com.trevorschoeny.sandboxes.sandbox;

import com.trevorschoeny.sandboxes.SandboxesClient;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Copying a world, and turning the copy into a sandbox.
 *
 * <p>A sandbox is a whole world on disk, because the player joins it as a real
 * world. Where the filesystem can share the blocks (APFS on macOS, btrfs and XFS
 * on Linux, ReFS on Windows) a copy is nearly free and costs space only as the
 * two worlds diverge. On NTFS it is a real copy, which is why the screen shows
 * each sandbox's size.
 */
public final class WorldFiles {

    private WorldFiles() {}

    /** Left behind by the copy: the parent's own sandboxes, and its lock file. */
    private static final List<String> SKIP_AT_ROOT =
            List.of(SandboxStore.SANDBOX_DIR, "session.lock");

    // Copying

    /**
     * Copies {@code source} to {@code dest}, minus the parent's own sandboxes
     * folder and its session lock.
     *
     * <p>Skipping {@code sandboxes/} is not an optimisation: sandboxes live
     * inside the world they copy, so without it every new sandbox would contain
     * a copy of all the others, and each sync would square that again.
     */
    public static void copyWorld(Path source, Path dest) throws IOException {
        if (cloneWorld(source, dest)) {
            // The clone copied everything, including the two things that must
            // not travel. Removing them afterwards is cheap and keeps the fast
            // path fast.
            for (String skip : SKIP_AT_ROOT) {
                Path stray = dest.resolve(skip);
                if (Files.isDirectory(stray)) deleteDirectory(stray);
                else Files.deleteIfExists(stray);
            }
            return;
        }
        copyTree(source, dest);
    }

    /**
     * Asks the OS for a copy-on-write clone. Returns false when the platform or
     * filesystem can't do it, so the caller falls back to a plain copy.
     */
    private static boolean cloneWorld(Path source, Path dest) {
        List<String> command = switch (Util.getPlatform()) {
            // -c clones on APFS, -a keeps the tree and its attributes.
            case OSX -> List.of("cp", "-c", "-a", source.toString(), dest.toString());
            // --reflink=auto clones on btrfs/XFS and silently copies elsewhere.
            case LINUX -> List.of("cp", "-a", "--reflink=auto", source.toString(), dest.toString());
            // Windows and everything else go through the Java path, which gets
            // ReFS block cloning for free where the OS offers it.
            default -> null;
        };
        if (command == null) return false;
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                SandboxesClient.LOGGER.warn("[sandboxes] clone timed out, copying instead");
                return false;
            }
            if (process.exitValue() == 0) return true;
            SandboxesClient.LOGGER.warn("[sandboxes] clone exited {}, copying instead",
                    process.exitValue());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            SandboxesClient.LOGGER.warn("[sandboxes] clone failed, copying instead", e);
        }
        // A failed clone can leave a partial tree; the plain copy needs clean ground.
        try {
            if (Files.exists(dest)) deleteDirectory(dest);
        } catch (IOException e) {
            SandboxesClient.LOGGER.error("[sandboxes] couldn't clear {} after a failed clone", dest, e);
        }
        return false;
    }

    /** Plain file-by-file copy, skipping the same two entries the clone path removes. */
    private static void copyTree(Path source, Path dest) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                if (skip(dir)) return FileVisitResult.SKIP_SUBTREE;
                Files.createDirectories(dest.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                if (!skip(file)) {
                    Files.copy(file, dest.resolve(source.relativize(file).toString()),
                            StandardCopyOption.COPY_ATTRIBUTES);
                }
                return FileVisitResult.CONTINUE;
            }

            /** Only at the world root: a chest named session.lock deeper down is data. */
            private boolean skip(Path path) {
                Path parent = path.getParent();
                return parent != null && parent.equals(source)
                        && SKIP_AT_ROOT.contains(path.getFileName().toString());
            }
        });
    }

    public static void deleteDirectory(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                if (e != null) throw e;
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Size on disk of a sandbox, for the row's size column. Best effort. */
    public static long sizeOf(Path dir) {
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).mapToLong(file -> {
                try {
                    return Files.size(file);
                } catch (IOException e) {
                    return 0L;
                }
            }).sum();
        } catch (IOException e) {
            return 0L;
        }
    }

    // Making the copy a sandbox

    /**
     * Turns a fresh copy into a sandbox: creative, commands on, named after the
     * sandbox rather than the world.
     *
     * <p>Hardcore is dropped to Hard difficulty. A hardcore sandbox would lock
     * itself on the first death, which is the opposite of a place to fail
     * freely, and the death would be in the copy where it means nothing.
     */
    public static void makeSandboxLevel(Path parentDir, Path sandboxDir, String name)
            throws IOException {
        Path levelFile = sandboxDir.resolve("level.dat");
        CompoundTag root = NbtIo.readCompressed(levelFile, NbtAccounter.uncompressedQuota());
        CompoundTag data = root.getCompound("Data").orElse(null);
        if (data == null) {
            throw new IOException("level.dat in " + sandboxDir + " has no Data compound");
        }

        data.putInt("GameType", 1);             // creative
        data.putBoolean("allowCommands", true);
        data.putString("LevelName", name);

        if (data.getBooleanOr("hardcore", false)) {
            data.putBoolean("hardcore", false);
            data.putInt("Difficulty", 3);       // hard, but survivable
        }

        // Singleplayer keeps the player inline at Data.Player rather than in
        // playerdata/, so the mode has to be set here too or the player arrives
        // in survival inside their own creative copy.
        CompoundTag player = data.getCompound("Player").orElse(null);
        if (player != null) {
            player.putInt("playerGameType", 1);
            // A v1 world carried its sandbox list on the player; a sandbox must
            // not inherit its parent's list.
            player.remove("trevormod_sandboxes");
        }

        NbtIo.writeCompressed(root, levelFile);
        patchPlayerData(sandboxDir);
    }

    /**
     * Where a world keeps its per-player files. 26.2 writes {@code players/data};
     * the older name is still read so a world carried forward from an earlier
     * version is patched too.
     */
    private static final List<String> PLAYER_DIRS = List.of("players/data", "playerdata");

    /** Sets creative on every per-player file the copy brought along. */
    private static void patchPlayerData(Path sandboxDir) {
        forEachPlayerFile(sandboxDir, file -> {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.uncompressedQuota());
            // A player file that still says survival beats the level's game type,
            // which is what put v2's first sandboxes in survival.
            tag.putInt("playerGameType", 1);
            tag.remove("trevormod_sandboxes");
            NbtIo.writeCompressed(tag, file);
        });
    }

    /** Applies {@code work} to every {@code .dat} player record under a world. */
    private static void forEachPlayerFile(Path worldDir, PlayerFileWork work) {
        for (String dir : PLAYER_DIRS) {
            Path playerDir = worldDir.resolve(dir);
            if (!Files.isDirectory(playerDir)) continue;
            try (Stream<Path> files = Files.list(playerDir)) {
                for (Path file : files.toList()) {
                    if (!file.getFileName().toString().endsWith(".dat")) continue;
                    try {
                        work.apply(file);
                    } catch (IOException e) {
                        // One unreadable player file shouldn't sink the sandbox.
                        SandboxesClient.LOGGER.warn("[sandboxes] couldn't patch {}", file, e);
                    }
                }
            } catch (IOException e) {
                SandboxesClient.LOGGER.warn("[sandboxes] couldn't list {}", playerDir, e);
            }
        }
    }

    private interface PlayerFileWork {
        void apply(Path file) throws IOException;
    }
}
