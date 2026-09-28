package com.elysium.vanguard.core.cloud

import android.content.Context
import com.elysium.vanguard.core.cloud.CloudConnectionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CloudModule {

    @Provides
    @Singleton
    fun provideCloudServiceFactory(@ApplicationContext context: Context): CloudServiceFactory {
        return DefaultCloudServiceFactory(context)
    }

    @Provides
    @Singleton
    fun provideCloudConnectionRepository(dao: CloudConnectionDao): CloudConnectionRepository {
        return RoomCloudConnectionRepository(dao)
    }
}