/*
 * NekoMusic
 * Copyright (C) 2026 NekoMusic contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package com.neko.music.search;

/**
 * FTS 查询字段范围。元数据命中与歌词命中必须分开查询，
 * 避免「元数据重新打分失败」被误判成歌词命中。
 */
public enum SearchScope {
    ALL,
    METADATA,
    LYRICS
}
