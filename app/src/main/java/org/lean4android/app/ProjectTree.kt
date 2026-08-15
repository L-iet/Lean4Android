package org.lean4android.app

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

internal enum class ProjectEntryIcon { Folder, LeanFile, File }

internal data class ProjectTreeRow(
    val path: String,
    val label: String,
    val depth: Int,
    val icon: ProjectEntryIcon,
    val expandable: Boolean,
)

private class ProjectTreeNode(
    val name: String,
    val path: String,
    var file: Boolean = false,
) {
    val children = sortedMapOf<String, ProjectTreeNode>(String.CASE_INSENSITIVE_ORDER)
}

internal fun projectEntryIcon(path: String, folder: Boolean): ProjectEntryIcon = when {
    folder -> ProjectEntryIcon.Folder
    path.endsWith(".lean", ignoreCase = true) -> ProjectEntryIcon.LeanFile
    else -> ProjectEntryIcon.File
}

internal fun projectTreeRows(paths: List<String>, expandedFolders: Set<String>): List<ProjectTreeRow> {
    val root = ProjectTreeNode("", "")
    paths.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER).forEach { path ->
        var current = root
        path.split('/').filter(String::isNotEmpty).forEachIndexed { index, part ->
            val childPath = current.path.takeIf(String::isNotEmpty)?.let { "$it/$part" } ?: part
            current = current.children.getOrPut(part) { ProjectTreeNode(part, childPath) }
            if (index == path.count { it == '/' }) current.file = true
        }
    }
    val rows = mutableListOf<ProjectTreeRow>()
    fun append(node: ProjectTreeNode, depth: Int) {
        if (node.file) {
            rows += ProjectTreeRow(node.path, node.name, depth, projectEntryIcon(node.path, false), false)
            return
        }
        var compact = node
        val labels = mutableListOf(node.name)
        while (!compact.file && compact.children.size == 1) {
            val child = compact.children.values.single()
            labels += child.name
            compact = child
        }
        if (compact.file) {
            rows += ProjectTreeRow(compact.path, labels.joinToString("/"), depth, projectEntryIcon(compact.path, false), false)
            return
        }
        rows += ProjectTreeRow(compact.path, labels.joinToString("/"), depth, ProjectEntryIcon.Folder, true)
        if (compact.path in expandedFolders) compact.children.values.forEach { append(it, depth + 1) }
    }
    root.children.values.forEach { append(it, 0) }
    return rows
}

internal fun insertEditorSymbol(value: TextFieldValue, symbol: String): TextFieldValue {
    val start = minOf(value.selection.start, value.selection.end)
    val end = maxOf(value.selection.start, value.selection.end)
    val text = value.text.replaceRange(start, end, symbol)
    return TextFieldValue(text, TextRange(start + symbol.length))
}
