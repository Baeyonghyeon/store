package com.example1.bddllm.worker;

import com.example1.bddllm.common.Problem;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component
public class ExecutionLocks {
    private final Path directory;

    public ExecutionLocks(@Value("${bdd.locks}") String path) {
        Path specified = Path.of(path);
        if (!specified.isAbsolute())
            throw new IllegalArgumentException("실행 잠금 폴더는 공유하는 절대 경로여야 합니다.");
        directory = specified.normalize();
    }

    public Lease acquire(String key) {
        UUID.fromString(key);
        FileChannel channel = null;
        try {
            Files.createDirectories(directory);
            channel =
                FileChannel.open(
                    directory.resolve(key + ".lock"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw busy();
            }
            return new Lease(channel, lock);
        } catch (OverlappingFileLockException exception) {
            try {
                if (channel != null) channel.close();
            } catch (IOException ignored) {
            }
            throw busy();
        } catch (IOException exception) {
            try {
                if (channel != null) channel.close();
            } catch (IOException ignored) {
            }
            throw new Problem(503, "execution_lock_unavailable", "작업 실행 잠금을 확인할 수 없습니다.");
        }
    }

    private Problem busy() {
        return new Problem(409, "execution_busy", "다른 실행 또는 복구 명령이 작업을 사용 중입니다.");
    }

    public record Lease(FileChannel channel, FileLock lock) implements AutoCloseable {
        public void close() {
            try {
                lock.release();
                channel.close();
            } catch (IOException exception) {
                throw new IllegalStateException("execution_lock_close_failed");
            }
        }
    }
}
