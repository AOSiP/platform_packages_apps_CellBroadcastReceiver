/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.cellbroadcastreceiver;

import android.annotation.NonNull;
import android.content.ContentProviderClient;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.RemoteException;
import android.provider.Telephony;
import android.util.Log;

/**
 * Open, create, and upgrade the cell broadcast SQLite database. Previously an inner class of
 * {@code CellBroadcastDatabase}, this is now a top-level class. The column definitions in
 * {@code CellBroadcastDatabase} have been moved to {@link Telephony.CellBroadcasts} in the
 * framework, to simplify access to this database from third-party apps.
 */
public class CellBroadcastDatabaseHelper extends SQLiteOpenHelper {

    private static final String TAG = "CellBroadcastDatabaseHelper";

    private static final String DATABASE_NAME = "cell_broadcasts.db";
    static final String TABLE_NAME = "broadcasts";

    /**
     * Database version 1: initial version (support removed)
     * Database version 2-9: (reserved for OEM database customization) (support removed)
     * Database version 10: adds ETWS and CMAS columns and CDMA support (support removed)
     * Database version 11: adds delivery time index
     * Database version 12: add slotIndex
     */
    private static final int DATABASE_VERSION = 12;

    private final Context mContext;

    CellBroadcastDatabaseHelper(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mContext = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                + Telephony.CellBroadcasts._ID + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + Telephony.CellBroadcasts.SLOT_INDEX + " INTEGER DEFAULT 0,"
                + Telephony.CellBroadcasts.GEOGRAPHICAL_SCOPE + " INTEGER,"
                + Telephony.CellBroadcasts.PLMN + " TEXT,"
                + Telephony.CellBroadcasts.LAC + " INTEGER,"
                + Telephony.CellBroadcasts.CID + " INTEGER,"
                + Telephony.CellBroadcasts.SERIAL_NUMBER + " INTEGER,"
                + Telephony.CellBroadcasts.SERVICE_CATEGORY + " INTEGER,"
                + Telephony.CellBroadcasts.LANGUAGE_CODE + " TEXT,"
                + Telephony.CellBroadcasts.MESSAGE_BODY + " TEXT,"
                + Telephony.CellBroadcasts.DELIVERY_TIME + " INTEGER,"
                + Telephony.CellBroadcasts.MESSAGE_READ + " INTEGER,"
                + Telephony.CellBroadcasts.MESSAGE_FORMAT + " INTEGER,"
                + Telephony.CellBroadcasts.MESSAGE_PRIORITY + " INTEGER,"
                + Telephony.CellBroadcasts.ETWS_WARNING_TYPE + " INTEGER,"
                + Telephony.CellBroadcasts.CMAS_MESSAGE_CLASS + " INTEGER,"
                + Telephony.CellBroadcasts.CMAS_CATEGORY + " INTEGER,"
                + Telephony.CellBroadcasts.CMAS_RESPONSE_TYPE + " INTEGER,"
                + Telephony.CellBroadcasts.CMAS_SEVERITY + " INTEGER,"
                + Telephony.CellBroadcasts.CMAS_URGENCY + " INTEGER,"
                + Telephony.CellBroadcasts.CMAS_CERTAINTY + " INTEGER);");

        db.execSQL("CREATE INDEX IF NOT EXISTS deliveryTimeIndex ON " + TABLE_NAME
                + " (" + Telephony.CellBroadcasts.DELIVERY_TIME + ");");
        migrateFromLegacy(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == newVersion) {
            return;
        }
        // always log database upgrade
        log("Upgrading DB from version " + oldVersion + " to " + newVersion);

        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN "
                    + Telephony.CellBroadcasts.SLOT_INDEX + " INTEGER DEFAULT 0;");
        }
    }

    /**
     * This is the migration logic to accommodate OEMs who previously use non-AOSP CBR and move to
     * mainlined CBR for the first time. When the db is initially created, this is called once to
     * migrate predefined data through {@link Telephony.CellBroadcasts#AUTHORITY_LEGACY_URI}
     * from OEM app.
     */
    private void migrateFromLegacy(@NonNull SQLiteDatabase db) {
        try (ContentProviderClient client = mContext.getContentResolver()
                .acquireContentProviderClient(Telephony.CellBroadcasts.AUTHORITY_LEGACY)) {
            if (client == null) {
                log("No legacy provider available for migration");
                return;
            }

            db.beginTransaction();
            log("Starting migration from legacy provider");
            // migration columns are same as query columns
            try (Cursor c = client.query(Telephony.CellBroadcasts.AUTHORITY_LEGACY_URI,
                    CellBroadcastContentProvider.QUERY_COLUMNS,
                    null, null, null)) {
                final ContentValues values = new ContentValues();
                while (c.moveToNext()) {
                    values.clear();
                    for (String column : CellBroadcastContentProvider.QUERY_COLUMNS) {
                        copyFromCursorToContentValues(column, c, values);
                    }

                    if (db.insert(TABLE_NAME, null, values) == -1) {
                        // We only have one shot to migrate data, so log and
                        // keep marching forward
                        loge("Failed to insert " + values + "; continuing");
                    }
                }

                db.setTransactionSuccessful();
                log("Finished migration from legacy provider");
            } catch (RemoteException e) {
                throw new IllegalStateException(e);
            } finally {
                db.endTransaction();
            }
        } catch (Exception e) {
            // We have to guard ourselves against any weird behavior of the
            // legacy provider by trying to catch everything
            loge("Failed migration from legacy provider: " + e);
        }

    }

    public static void copyFromCursorToContentValues(@NonNull String column, @NonNull Cursor cursor,
            @NonNull ContentValues values) {
        final int index = cursor.getColumnIndex(column);
        if (index != -1) {
            if (cursor.isNull(index)) {
                values.putNull(column);
            } else {
                values.put(column, cursor.getString(index));
            }
        }
    }

    private static void log(String msg) {
        Log.d(TAG, msg);
    }

    private static void loge(String msg) {
        Log.e(TAG, msg);
    }
}
