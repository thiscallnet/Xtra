package com.github.andreyasadchy.xtra.db

import androidx.room.migration.Migration

object EmoteUsageMigrations {
    val FROM_51 = Migration(51, 52) { db ->
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS emote_usage (
                viewer_id TEXT NOT NULL,
                usage_key TEXT NOT NULL,
                provider TEXT NOT NULL,
                emote_id TEXT NOT NULL,
                scope TEXT NOT NULL,
                channel_id TEXT,
                use_count INTEGER NOT NULL,
                last_used_at INTEGER NOT NULL,
                PRIMARY KEY(viewer_id, usage_key)
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_emote_usage_viewer_id_channel_id ON emote_usage(viewer_id, channel_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_emote_usage_viewer_id_provider_emote_id ON emote_usage(viewer_id, provider, emote_id)")
    }
}
