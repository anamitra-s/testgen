package com.company.testgen.validation;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AriaRolesAndAccessibleNameTest {

    @Test
    @DisplayName("AriaRoles validates standard ARIA roles and computes implicit roles")
    void testAriaRoles() {
        assertThat(AriaRoles.isValid("button")).isTrue();
        assertThat(AriaRoles.isValid("textbox")).isTrue();
        assertThat(AriaRoles.isValid("heading")).isTrue();
        assertThat(AriaRoles.isValid("combobox")).isTrue();
        assertThat(AriaRoles.isValid("invalid-role")).isFalse();
        assertThat(AriaRoles.isValid("")).isFalse();
        assertThat(AriaRoles.isValid(null)).isFalse();

        Document doc = Jsoup.parse("""
                <button id="b1">Click</button>
                <a id="a1" href="/home">Home</a>
                <a id="a2">Not a link</a>
                <input id="i1" type="text" />
                <input id="i2" type="checkbox" />
                <input id="i3" type="radio" />
                <input id="i4" type="submit" />
                <div id="d1" role="search">Search box</div>
                """);

        assertThat(AriaRoles.effectiveRole(doc.getElementById("b1"))).isEqualTo("button");
        assertThat(AriaRoles.effectiveRole(doc.getElementById("a1"))).isEqualTo("link");
        assertThat(AriaRoles.effectiveRole(doc.getElementById("a2"))).isNull();
        assertThat(AriaRoles.effectiveRole(doc.getElementById("i1"))).isEqualTo("textbox");
        assertThat(AriaRoles.effectiveRole(doc.getElementById("i2"))).isEqualTo("checkbox");
        assertThat(AriaRoles.effectiveRole(doc.getElementById("i3"))).isEqualTo("radio");
        assertThat(AriaRoles.effectiveRole(doc.getElementById("i4"))).isEqualTo("button");
        assertThat(AriaRoles.effectiveRole(doc.getElementById("d1"))).isEqualTo("search");
    }

    @Test
    @DisplayName("AccessibleName calculates accessible names conservatively according to W3C order")
    void testAccessibleNameComputation() {
        Document doc = Jsoup.parse("""
                <span id="title">User Details</span>
                <button id="b1" aria-labelledby="title">Fallback</button>
                <button id="b2" aria-labelledby="missing-id">Fallback</button>
                <button id="b3" aria-label="Close dialog">X</button>
                <label for="username">Username Field</label>
                <input id="username" type="text" />
                <label>Wrapping Label <input id="email" type="text" /></label>
                <img id="logo" src="/logo.png" alt="Company Logo" />
                <a id="link1" href="/contact">Contact Us</a>
                """);

        // aria-labelledby
        assertThat(AccessibleName.compute(doc.getElementById("b1"))).isEqualTo("User Details");
        // missing reference returns null (conservative)
        assertThat(AccessibleName.compute(doc.getElementById("b2"))).isNull();

        // aria-label
        assertThat(AccessibleName.compute(doc.getElementById("b3"))).isEqualTo("Close dialog");

        // label[for]
        assertThat(AccessibleName.compute(doc.getElementById("username"))).isEqualTo("Username Field");

        // wrapping label
        assertThat(AccessibleName.compute(doc.getElementById("email"))).isEqualTo("Wrapping Label");

        // img alt
        assertThat(AccessibleName.compute(doc.getElementById("logo"))).isEqualTo("Company Logo");

        // text content
        assertThat(AccessibleName.compute(doc.getElementById("link1"))).isEqualTo("Contact Us");
    }
}
