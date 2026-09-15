package com.pn.zenify.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Shared list scaffolding so every screen groups rows the same way: a small
 * uppercase section header, then rows stacked into one rounded "card" that
 * stays lazy (each row is its own LazyColumn item — long lists never compose
 * all at once).
 */

/** Section header: uppercase label on the left, optional hint on the right. */
@Composable
fun SectionHeader(
    title: String,
    hint: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (hint != null) {
            Text(
                text = hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val GroupRadius = 20.dp

/** Corner shape for the [index]th of [count] rows so a run of rows reads as one card. */
fun groupShape(index: Int, count: Int): Shape {
    val top = if (index == 0) GroupRadius else 0.dp
    val bottom = if (index == count - 1) GroupRadius else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * One row of a grouped card. Rows are separated by an inset divider; the first
 * and last rows carry the card's rounded corners.
 */
@Composable
fun GroupedRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    dividerInset: androidx.compose.ui.unit.Dp = 72.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = groupShape(index, count),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column {
            content()
            if (index < count - 1) {
                HorizontalDivider(
                    modifier = Modifier.padding(start = dividerInset),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

/** Lazily emit [items] as one grouped card; [keyPrefix] keeps keys unique across sections. */
fun <T> LazyListScope.groupedItems(
    items: List<T>,
    keyPrefix: String,
    key: (T) -> Any,
    row: @Composable (T) -> Unit,
) {
    itemsIndexed(items, key = { _, item -> "${keyPrefix}_${key(item)}" }) { index, item ->
        GroupedRow(index = index, count = items.size) { row(item) }
    }
}

/** A single, non-lazy grouped card holding [content] (for short static groups). */
@Composable
fun GroupCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(GroupRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column { content() }
    }
}
