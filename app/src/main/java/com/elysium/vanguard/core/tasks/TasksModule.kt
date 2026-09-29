package com.elysium.vanguard.core.tasks

import com.elysium.vanguard.core.trash.TrashRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Binds the lambda-injected [ScheduledTaskExecutor] to the real world:
 * trash purge via [TrashRepository], directory copy via
 * [ScheduledTaskExecutor.copyRecursive].
 */
@Module
@InstallIn(SingletonComponent::class)
object TasksModule {

    @Provides
    @Singleton
    fun provideScheduledTaskExecutor(trashRepository: TrashRepository): ScheduledTaskExecutor =
        ScheduledTaskExecutor(
            purgeTrash = { trashRepository.purgeAll() },
            copyDirectory = { source: File, dest: File ->
                ScheduledTaskExecutor.copyRecursive(source, dest)
            },
        )
}
