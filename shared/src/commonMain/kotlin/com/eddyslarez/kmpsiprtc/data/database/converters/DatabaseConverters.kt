package com.eddyslarez.kmpsiprtc.data.database.converters

import androidx.room.TypeConverter
import com.eddyslarez.kmpsiprtc.data.models.*

class DatabaseConverters {

    // CallState converters
    @TypeConverter
    fun fromCallState(value: CallState): String = value.name

    @TypeConverter
    fun toCallState(value: String): CallState {
        return try {
            CallState.valueOf(value)
        } catch (e: IllegalArgumentException) {
            CallState.IDLE
        }
    }

    // CallDirections converters
    @TypeConverter
    fun fromCallDirection(value: CallDirections): String = value.name

    @TypeConverter
    fun toCallDirection(value: String): CallDirections {
        return try {
            CallDirections.valueOf(value)
        } catch (e: IllegalArgumentException) {
            CallDirections.INCOMING
        }
    }

    // CallTypes converters
    @TypeConverter
    fun fromCallType(value: CallTypes): String = value.name

    @TypeConverter
    fun toCallType(value: String): CallTypes {
        return try {
            CallTypes.valueOf(value)
        } catch (e: IllegalArgumentException) {
            CallTypes.MISSED
        }
    }

    // RegistrationState converters
    @TypeConverter
    fun fromRegistrationState(value: RegistrationState): String = value.name

    @TypeConverter
    fun toRegistrationState(value: String): RegistrationState {
        return try {
            RegistrationState.valueOf(value)
        } catch (e: IllegalArgumentException) {
            RegistrationState.NONE
        }
    }

    // CallErrorReason converters
    @TypeConverter
    fun fromCallErrorReason(value: CallErrorReason): String = value.name

    @TypeConverter
    fun toCallErrorReason(value: String): CallErrorReason {
        return try {
            CallErrorReason.valueOf(value)
        } catch (e: IllegalArgumentException) {
            CallErrorReason.NONE
        }
    }
}