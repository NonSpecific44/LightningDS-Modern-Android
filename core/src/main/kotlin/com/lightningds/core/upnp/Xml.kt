package com.lightningds.core.upnp

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Small DOM helpers. Parsing is namespace-unaware on purpose: UPnP devices (and the
 * Lightning Server in particular) are not always strict about namespaces, so we match
 * on local names (the part after the prefix).
 */
internal object Xml {
    private val factory: DocumentBuilderFactory by lazy {
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
            isCoalescing = true
            trySet("http://apache.org/xml/features/disallow-doctype-decl", true)
            trySet("http://xml.org/sax/features/external-general-entities", false)
            trySet("http://xml.org/sax/features/external-parameter-entities", false)
        }
    }

    private fun DocumentBuilderFactory.trySet(feature: String, value: Boolean) {
        try {
            setFeature(feature, value)
        } catch (_: Exception) {
        }
    }

    fun parse(text: String): Document {
        val builder = synchronized(factory) { factory.newDocumentBuilder() }
        return builder.parse(InputSource(StringReader(sanitize(text))))
    }

    fun parseOrNull(text: String?): Document? {
        if (text.isNullOrBlank()) return null
        return try {
            parse(text)
        } catch (_: Exception) {
            null
        }
    }

    /** Removes characters that are illegal in XML 1.0 and a leading BOM. */
    fun sanitize(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val ok = c == '\t' || c == '\n' || c == '\r' || (c in ' '..'퟿') ||
                (c in ''..'�') || Character.isSurrogate(c)
            if (ok && c != '﻿') sb.append(c)
        }
        return sb.toString().trimStart()
    }

    fun escape(text: String?): String {
        if (text == null) return ""
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }
}

internal val Node.localName2: String
    get() = nodeName.substringAfter(':')

internal fun Element.children(): List<Element> {
    val out = ArrayList<Element>()
    val nodes = childNodes
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n is Element) out += n
    }
    return out
}

internal fun Element.children(name: String): List<Element> = children().filter { it.localName2 == name }

internal fun Element.child(name: String): Element? = children().firstOrNull { it.localName2 == name }

internal fun Element.childText(name: String): String? = child(name)?.textContent?.trim()

/** Depth-first search for the first descendant with the given local name. */
internal fun Element.descendant(name: String): Element? {
    for (c in children()) {
        if (c.localName2 == name) return c
        c.descendant(name)?.let { return it }
    }
    return null
}

internal fun Element.attr(name: String): String? {
    val attrs = attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if (a.nodeName == name || a.nodeName.substringAfter(':') == name) return a.nodeValue
    }
    return null
}
