package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalDateTime;

public record Anchor(LocalDateTime start, LocalDateTime end) {
}
