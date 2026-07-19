package com.flowpilot.todo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ETagsTest {
    @Test
    void parsesAndFormatsStrongVersionEtag() {
        assertThat(ETags.parse("\"0\"")).isZero();
        assertThat(ETags.parse("\"9223372036854775807\""))
            .isEqualTo(Long.MAX_VALUE);
        assertThat(ETags.format(7)).isEqualTo("\"7\"");
    }

    @Test
    void missingIfMatchRequiresPrecondition() {
        assertThatThrownBy(() -> ETags.parse(null))
            .isInstanceOf(PreconditionRequired.class);
    }

    @Test
    void malformedIfMatchIsRejected() {
        assertThatThrownBy(() -> ETags.parse("7"))
            .isInstanceOf(BadRequest.class)
            .hasMessage("If-Match must be a quoted version");
        assertThatThrownBy(() -> ETags.parse("W/\"7\""))
            .isInstanceOf(BadRequest.class)
            .hasMessage("If-Match must be a quoted version");
        assertThatThrownBy(() -> ETags.parse("\"-1\""))
            .isInstanceOf(BadRequest.class)
            .hasMessage("If-Match must be a quoted version");
    }

    @Test
    void outOfRangeVersionIsRejected() {
        assertThatThrownBy(() -> ETags.parse("\"9223372036854775808\""))
            .isInstanceOf(BadRequest.class)
            .hasMessage("If-Match version is out of range");
    }
}
