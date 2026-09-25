package com.aiinterviewer.dto;

import java.util.List;

public record PageResponse<T>(long total, long page, long size, List<T> list) {
}
