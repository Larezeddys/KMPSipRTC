package com.eddyslarez.kmpsiprtc.services.matrix

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.folivo.trixnity.core.model.events.m.Mentions
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContentSerializer
import net.folivo.trixnity.core.serialization.events.DefaultEventContentSerializerMappings
import net.folivo.trixnity.core.serialization.events.EventContentSerializerMappings
import net.folivo.trixnity.core.serialization.events.MessageEventContentSerializerMapping
import org.koin.core.module.Module
import org.koin.dsl.module

/** Keeps BackOffice extensions attached to otherwise standard Matrix text events. */
internal object BackofficeRoomMessageEventContentSerializer : KSerializer<RoomMessageEventContent> {
    override val descriptor: SerialDescriptor = RoomMessageEventContentSerializer.descriptor

    override fun deserialize(decoder: Decoder): RoomMessageEventContent {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("BackOffice room messages require JSON")
        val element = jsonDecoder.decodeJsonElement()
        val raw = element as? JsonObject
            ?: throw SerializationException("Room message content must be a JSON object")
        val messageType = raw["msgtype"]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("msgtype must not be null")
        val hasBackofficeFields = raw.keys.any { it.startsWith("com.backoffice.") }
        if (!hasBackofficeFields || messageType !in setOf("m.text", "m.notice", "m.emote")) {
            return jsonDecoder.json.decodeFromJsonElement(RoomMessageEventContentSerializer, element)
        }

        val body = raw["body"]?.jsonPrimitive?.contentOrNull
            ?: throw SerializationException("body must not be null")
        val relation = raw["m.relates_to"]?.let { runCatching {
            jsonDecoder.json.decodeFromJsonElement(RelatesTo.serializer(), it)
        }.getOrNull() }
        val mentions = raw["m.mentions"]?.let { runCatching {
            jsonDecoder.json.decodeFromJsonElement(Mentions.serializer(), it)
        }.getOrNull() }

        return RoomMessageEventContent.Unknown(
            type = messageType,
            body = body,
            raw = raw,
            format = raw["format"]?.jsonPrimitive?.contentOrNull,
            formattedBody = raw["formatted_body"]?.jsonPrimitive?.contentOrNull,
            relatesTo = relation,
            mentions = mentions,
            externalUrl = raw["external_url"]?.jsonPrimitive?.contentOrNull,
        )
    }

    override fun serialize(encoder: Encoder, value: RoomMessageEventContent) {
        RoomMessageEventContentSerializer.serialize(encoder as? JsonEncoder
            ?: throw SerializationException("BackOffice room messages require JSON"), value)
    }
}

internal fun createBackofficeEventContentSerializerMappingsModule(): Module = module {
    single<EventContentSerializerMappings> {
        val defaults = DefaultEventContentSerializerMappings
        object : EventContentSerializerMappings by defaults {
            override val message = defaults.message
                .filterNot { it.type == "m.room.message" }
                .toSet() + MessageEventContentSerializerMapping(
                type = "m.room.message",
                kClass = RoomMessageEventContent::class,
                serializer = BackofficeRoomMessageEventContentSerializer,
            )
        }
    }
}
