package com.unisaarland.se.findingsnavigator.util

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper
import com.unisaarland.se.findingsnavigator.model.Finding
import com.unisaarland.se.findingsnavigator.model.Location
import org.yaml.snakeyaml.LoaderOptions
import java.io.File

object FindingsYamlParser {
    data class ParseResult(val findings: List<Finding>, val nodeMap: Map<String, ObjectNode>, val root: ObjectNode?)

    /**
     * Parse a findings YAML file into a list of Finding objects.
     * The input is expected to be a mapping from category -> list of entries.
     * Returns the findings and a mapping from generated finding id -> the underlying ObjectNode
     * representing the YAML entry so callers can update the node directly.
     */
    fun parse(file: File): ParseResult {

        val loaderOptions = LoaderOptions()
        loaderOptions.codePointLimit = 100 * 1024 * 1024 // 100 MB
        val yamlFactory = YAMLFactory.builder()
            .loaderOptions(loaderOptions)
            .build()

        val yamlMapper = YAMLMapper(yamlFactory)

        val root: JsonNode = yamlMapper.readTree(file)

        val findings = mutableListOf<Finding>()
        val nodeMap = mutableMapOf<String, ObjectNode>()

        if (root.isObject) {
            val fields = root.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val category = entry.key
                val arr = entry.value
                if (!arr.isArray) continue
                for (item in arr) {
                    // Each item may contain a nested "Location" object and other fields
                    val locationNode = item.get("Location") ?: item.get("location")
                    val filename = locationNode?.get("Filename")?.asText()
                        ?: locationNode?.get("filename")?.asText()
                        ?: item.get("file")?.asText()
                        ?: continue

                    val lineno = locationNode?.get("Lineno")?.asInt() ?: locationNode?.get("lineno")?.asInt()
                    val colno = locationNode?.get("Colno")?.asInt() ?: locationNode?.get("colno")?.asInt()

                    val tagsNode = item.get("tags")
                    val tags = mutableSetOf<String>()
                    if (tagsNode != null && tagsNode.isArray) {
                        for (t in tagsNode) {
                            tags.add(t.asText())
                        }
                    }

                    // collect extras: any fields that are not Location or tags
                    val extras = mutableMapOf<String, String>()
                    val it = item.fields()
                    while (it.hasNext()) {
                        val e = it.next()
                        val key = e.key
                        if (key == "Location" || key == "location" || key == "tags") continue
                        // stringify the value for display
                        extras[key] = e.value.toString()
                    }

                    // create id: category + filename + lineno + index to be unique-ish
                    val id = "${category}:${filename}:${lineno ?: 0}:${colno ?: 0}:${findings.size}"

                    val loc = Location(filename, lineno, colno)
                    val f = Finding(id = id, category = category, location = loc, tags = tags, extras = extras)
                    findings.add(f)

                    if (item.isObject) {
                        nodeMap[id] = item as ObjectNode
                    }
                }
            }
        }

        return ParseResult(findings.toList(), nodeMap, if (root.isObject) root as ObjectNode else null)
    }
}
