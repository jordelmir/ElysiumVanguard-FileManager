package com.elysium.vanguard.core.sheet

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

/**
 * PHASE 10.6 — JSON adapter for [SheetWorkbook].
 *
 * `.elysium.sheet` is the canonical on-disk format for Elysium
 * Sheet. It is a single JSON object that round-trips losslessly.
 * The schema lives in this file so any change is visible
 * alongside the model.
 *
 * Schema (compact form):
 * ```
 * {
 *   "title": "...",
 *   "author": "...",
 *   "revision": 4,
 *   "active": 0,
 *   "namedRanges": { "sales": "Sheet1!A1:A10" },
 *   "sheets": [
 *     {
 *       "name": "Sheet1",
 *       "defaultColWidth": 8.43,
 *       "defaultRowHeight": 15.0,
 *       "frozenRows": 0, "frozenCols": 0,
 *       "colWidths": { "1": 12.0 },
 *       "rowHeights": { "1": 18.0 },
 *       "cells": {
 *         "A1": { "value": "10", "formula": null,
 *                 "format": { ... }, "align": { ... },
 *                 "comment": null, "hyperlink": null }
 *       }
 *     }
 *   ]
 * }
 * ```
 */
object SheetJson {
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(SheetCell::class.java, CellAdapter)
        .registerTypeAdapter(CellFormat::class.java, CellFormatAdapter)
        .registerTypeAdapter(CellAlignment::class.java, AlignmentAdapter)
        .registerTypeAdapter(NumberFormat::class.java, NumberFormatAdapter)
        .setPrettyPrinting()
        .create()

    fun toJson(workbook: SheetWorkbook): String = gson.toJson(workbook)
    fun fromJson(text: String): SheetWorkbook = gson.fromJson(text, SheetWorkbook::class.java)

    // ── Adapters ──────────────────────────────────────────────────

    private object CellAdapter : JsonSerializer<SheetCell>, JsonDeserializer<SheetCell> {
        override fun serialize(src: SheetCell, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            o.addProperty("value", src.value)
            src.formula?.let { o.addProperty("formula", it) }
            o.add("format", ctx.serialize(src.format))
            o.add("align", ctx.serialize(src.alignment))
            src.comment?.let { o.addProperty("comment", it) }
            src.hyperlink?.let { o.addProperty("hyperlink", it) }
            return o
        }
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): SheetCell {
            val o = json.asJsonObject
            return SheetCell(
                value = o.get("value")?.takeUnless { it.isJsonNull }?.asString ?: "",
                formula = o.get("formula")?.takeUnless { it.isJsonNull }?.asString,
                format = o.get("format")?.let {
                    ctx.deserialize<CellFormat>(it, CellFormat::class.java)
                } ?: CellFormat(),
                alignment = o.get("align")?.let {
                    ctx.deserialize<CellAlignment>(it, CellAlignment::class.java)
                } ?: CellAlignment(),
                comment = o.get("comment")?.takeUnless { it.isJsonNull }?.asString,
                hyperlink = o.get("hyperlink")?.takeUnless { it.isJsonNull }?.asString
            )
        }
    }

    private object CellFormatAdapter : JsonSerializer<CellFormat>, JsonDeserializer<CellFormat> {
        override fun serialize(src: CellFormat, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            o.addProperty("font", src.fontFamily)
            o.addProperty("size", src.fontSizeSp)
            o.addProperty("bold", src.bold)
            o.addProperty("italic", src.italic)
            o.addProperty("underline", src.underline)
            o.addProperty("strike", src.strikethrough)
            src.color?.let { o.addProperty("color", "0x%08X".format(it)) }
            src.fill?.let { o.addProperty("fill", "0x%08X".format(it)) }
            o.addProperty("fillPattern", src.fillPattern.name)
            src.borderTop?.let { o.add("borderTop", ctx.serialize(it)) }
            src.borderRight?.let { o.add("borderRight", ctx.serialize(it)) }
            src.borderBottom?.let { o.add("borderBottom", ctx.serialize(it)) }
            src.borderLeft?.let { o.add("borderLeft", ctx.serialize(it)) }
            o.add("numberFormat", ctx.serialize(src.numberFormat))
            return o
        }
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): CellFormat {
            val o = json.asJsonObject
            return CellFormat(
                fontFamily = o.get("font")?.takeUnless { it.isJsonNull }?.asString ?: "sans-serif",
                fontSizeSp = o.get("size")?.asFloat ?: 12f,
                bold = o.get("bold")?.asBoolean ?: false,
                italic = o.get("italic")?.asBoolean ?: false,
                underline = o.get("underline")?.asBoolean ?: false,
                strikethrough = o.get("strike")?.asBoolean ?: false,
                color = o.get("color")?.takeUnless { it.isJsonNull }?.asString?.let(::parseHex),
                fill = o.get("fill")?.takeUnless { it.isJsonNull }?.asString?.let(::parseHex),
                fillPattern = runCatching {
                    FillPattern.valueOf((o.get("fillPattern")?.asString ?: "SOLID").uppercase())
                }.getOrDefault(FillPattern.SOLID),
                borderTop = o.get("borderTop")?.let { ctx.deserialize<BorderSide>(it, BorderSide::class.java) },
                borderRight = o.get("borderRight")?.let { ctx.deserialize<BorderSide>(it, BorderSide::class.java) },
                borderBottom = o.get("borderBottom")?.let { ctx.deserialize<BorderSide>(it, BorderSide::class.java) },
                borderLeft = o.get("borderLeft")?.let { ctx.deserialize<BorderSide>(it, BorderSide::class.java) },
                numberFormat = o.get("numberFormat")?.let {
                    ctx.deserialize<NumberFormat>(it, NumberFormat::class.java)
                } ?: NumberFormat.GENERAL
            )
        }

        private fun parseHex(s: String): Long {
            val cleaned = s.removePrefix("0x").removePrefix("0X")
            return cleaned.toLong(16)
        }
    }

    private object BorderSideAdapter : JsonSerializer<BorderSide>, JsonDeserializer<BorderSide> {
        override fun serialize(src: BorderSide, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            o.addProperty("style", src.style.name)
            o.addProperty("color", "0x%08X".format(src.color))
            return o
        }
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): BorderSide {
            val o = json.asJsonObject
            return BorderSide(
                style = runCatching {
                    BorderStyle.valueOf((o.get("style")?.asString ?: "THIN").uppercase())
                }.getOrDefault(BorderStyle.THIN),
                color = o.get("color")?.asString?.removePrefix("0x")?.removePrefix("0X")?.toLong(16)
                    ?: 0xFF2A2F35
            )
        }
    }

    private object AlignmentAdapter : JsonSerializer<CellAlignment>, JsonDeserializer<CellAlignment> {
        override fun serialize(src: CellAlignment, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            o.addProperty("h", src.horizontal.name)
            o.addProperty("v", src.vertical.name)
            o.addProperty("wrap", src.wrap)
            o.addProperty("indent", src.indent)
            return o
        }
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): CellAlignment {
            val o = json.asJsonObject
            return CellAlignment(
                horizontal = runCatching {
                    HorizontalAlignment.valueOf((o.get("h")?.asString ?: "GENERAL").uppercase())
                }.getOrDefault(HorizontalAlignment.GENERAL),
                vertical = runCatching {
                    VerticalAlignment.valueOf((o.get("v")?.asString ?: "BOTTOM").uppercase())
                }.getOrDefault(VerticalAlignment.BOTTOM),
                wrap = o.get("wrap")?.asBoolean ?: false,
                indent = o.get("indent")?.asInt ?: 0
            )
        }
    }

    private object NumberFormatAdapter : JsonSerializer<NumberFormat>, JsonDeserializer<NumberFormat> {
        override fun serialize(src: NumberFormat, type: Type, ctx: JsonSerializationContext): JsonElement {
            val o = JsonObject()
            when (src) {
                NumberFormat.GENERAL -> o.addProperty("kind", "general")
                is NumberFormat.NUMBER -> {
                    o.addProperty("kind", "number")
                    o.addProperty("decimals", src.decimals)
                }
                is NumberFormat.CURRENCY -> {
                    o.addProperty("kind", "currency")
                    o.addProperty("symbol", src.symbol)
                    o.addProperty("decimals", src.decimals)
                }
                is NumberFormat.PERCENT -> {
                    o.addProperty("kind", "percent")
                    o.addProperty("decimals", src.decimals)
                }
                NumberFormat.DATE -> o.addProperty("kind", "date")
                NumberFormat.TIME -> o.addProperty("kind", "time")
                is NumberFormat.SCIENTIFIC -> {
                    o.addProperty("kind", "scientific")
                    o.addProperty("decimals", src.decimals)
                }
                is NumberFormat.FRACTION -> {
                    o.addProperty("kind", "fraction")
                    o.addProperty("denominator", src.denominator)
                }
                is NumberFormat.CUSTOM -> {
                    o.addProperty("kind", "custom")
                    o.addProperty("pattern", src.pattern)
                }
            }
            return o
        }
        override fun deserialize(json: JsonElement, type: Type, ctx: JsonDeserializationContext): NumberFormat {
            val o = json.asJsonObject
            val kind = o.get("kind")?.asString ?: "general"
            return when (kind) {
                "general" -> NumberFormat.GENERAL
                "number" -> NumberFormat.NUMBER(o.get("decimals")?.asInt ?: 0)
                "currency" -> NumberFormat.CURRENCY(
                    o.get("symbol")?.asString ?: "$",
                    o.get("decimals")?.asInt ?: 2
                )
                "percent" -> NumberFormat.PERCENT(o.get("decimals")?.asInt ?: 0)
                "date" -> NumberFormat.DATE
                "time" -> NumberFormat.TIME
                "scientific" -> NumberFormat.SCIENTIFIC(o.get("decimals")?.asInt ?: 2)
                "fraction" -> NumberFormat.FRACTION(o.get("denominator")?.asInt ?: 16)
                "custom" -> NumberFormat.CUSTOM(o.get("pattern")?.asString ?: "0")
                else -> NumberFormat.GENERAL
            }
        }
    }
}

/**
 * PHASE 10.6 — Top-level helper: open a `.elysium.sheet` file (or
 * a blank workbook when the path doesn't exist) and save it back.
 */
object SheetFile {
    private val magic = "ELYSIUM-SHEET/1\n"

    fun read(text: String): SheetWorkbook {
        val payload = if (text.startsWith(magic)) text.removePrefix(magic) else text
        return SheetJson.fromJson(payload)
    }

    fun write(workbook: SheetWorkbook): String = magic + SheetJson.toJson(workbook)

    fun readFile(path: java.io.File): SheetWorkbook = read(path.readText())
    fun writeFile(path: java.io.File, workbook: SheetWorkbook) {
        path.parentFile?.mkdirs()
        path.writeText(write(workbook))
    }
}
