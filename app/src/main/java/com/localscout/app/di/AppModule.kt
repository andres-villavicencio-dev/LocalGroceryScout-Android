package com.localscout.app.di

import android.content.Context
import com.localscout.app.data.settings.SettingsRepository
import com.localscout.app.data.shoppinglist.ShoppingListRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun provideSettingsRepository(
        @ApplicationContext context: Context,
    ): SettingsRepository = SettingsRepository(context)

    @Provides
    @Singleton
    fun provideShoppingListRepository(
        @ApplicationContext context: Context,
    ): ShoppingListRepository = ShoppingListRepository(context)
}
