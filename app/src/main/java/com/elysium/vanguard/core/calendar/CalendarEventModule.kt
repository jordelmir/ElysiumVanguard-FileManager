package com.elysium.vanguard.core.calendar

import com.elysium.vanguard.core.database.CalendarEventDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CalendarEventModule {
    @Provides
    @Singleton
    fun provideCalendarEventRepository(
        dao: CalendarEventDao,
    ): CalendarEventRepository = CalendarEventRepository(dao)
}
