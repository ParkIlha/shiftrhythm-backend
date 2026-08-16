package com.shiftrhythm.backend.domain.schedule;

import java.time.LocalTime;

public record Anchor(LocalTime start, LocalTime end) {
}
