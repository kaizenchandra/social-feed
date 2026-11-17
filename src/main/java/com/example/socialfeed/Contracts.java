package com.example.socialfeed;

import jakarta.validation.constraints.*;

import java.util.*;

public final class Contracts {
    private Contracts() {
    }

    public static boolean transition(State from, State to) {
        return from == to || switch (from) {
            case DRAFT -> to == State.PUBLISHED || to == State.PROCESSING || to == State.DELETED;
            case PROCESSING -> to == State.PUBLISHED || to == State.DELETED;
            case PUBLISHED -> to == State.HIDDEN || to == State.DELETED;
            case HIDDEN -> to == State.PUBLISHED || to == State.DELETED;
            case DELETED -> false;
        };
    }

    public enum Visibility {PUBLIC, FOLLOWERS, SELF}

    public enum State {DRAFT, PROCESSING, PUBLISHED, HIDDEN, DELETED}

    public record Profile(@NotBlank @Size(max = 100) String displayName, boolean privateAccount, boolean active) {
    }

    public record CreatePost(@NotNull @Size(max = 5000) String caption, @NotNull Visibility visibility,
                             @NotNull State state, @Size(max = 4) List<UUID> media) {
    }

    public record EditPost(@NotNull @Size(max = 5000) String caption, @NotNull Visibility visibility,
                           @NotNull State state, @Min(0) long version) {
    }

    public record Comment(@NotBlank @Size(max = 2000) String body) {
    }

    public record Upload(@Pattern(regexp = "image/(jpeg|png)|video/mp4") @NotNull String contentType,
                         @Min(1) @Max(104857600) long bytes) {
    }

    public record Page(List<Map<String, Object>> items, String nextCursor) {
    }
}
