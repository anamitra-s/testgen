package com.company.testgen.validation;

import com.company.testgen.model.LocatorStrategy;
import com.company.testgen.model.ResolvedLocator;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DOM Fixture Corpus Tests for JsoupLocatorTranslator (§6.5, §9.5).
 * Tests AriaRoles.effectiveRole and AccessibleName.compute across:
 * - TEST_ID
 * - ROLE_NAME
 * - LABEL
 * - PLACEHOLDER
 * - TEXT
 * Verifies conservative rejection on ambiguous aria-labelledby attributes.
 */
class JsoupLocatorTranslatorTest {

    // =========================================================================
    // 1. Parameterized Tests: AriaRoles.effectiveRole
    // =========================================================================

    @ParameterizedTest(name = "[{index}] Selector: {1} in {0} -> expected role: {2}")
    @CsvSource(delimiter = '|', value = {
            "<button>Click me</button> | button | button",
            "<input type='button' value='OK' /> | input | button",
            "<input type='submit' value='Send' /> | input | button",
            "<input type='reset' value='Reset' /> | input | button",
            "<input type='image' alt='Go' /> | input | button",
            "<input type='checkbox' /> | input | checkbox",
            "<input type='radio' /> | input | radio",
            "<input type='search' /> | input | searchbox",
            "<input type='range' /> | input | slider",
            "<input type='number' /> | input | spinbutton",
            "<input type='text' /> | input | textbox",
            "<input type='email' /> | input | textbox",
            "<input type='password' /> | input | textbox",
            "<input type='tel' /> | input | textbox",
            "<input type='url' /> | input | textbox",
            "<input /> | input | textbox",
            "<textarea></textarea> | textarea | textbox",
            "<select><option>A</option></select> | select | combobox",
            "<h1>Header 1</h1> | h1 | heading",
            "<h2>Header 2</h2> | h2 | heading",
            "<h3>Header 3</h3> | h3 | heading",
            "<a href='https://example.com'>Link</a> | a | link",
            "<dialog open>Modal</dialog> | dialog | dialog",
            "<img src='cat.png' alt='Cat' /> | img | img",
            "<ul><li>Item</li></ul> | ul | list",
            "<ul><li>Item</li></ul> | li | listitem",
            "<table><tbody><tr><td>Cell</td></tr></tbody></table> | table | table",
            "<table><tbody><tr><td>Cell</td></tr></tbody></table> | tr | row",
            "<table><thead><tr><th scope='col'>Header</th></tr></thead></table> | th | columnheader",
            "<table><tbody><tr><th scope='row'>Row Header</th></tr></tbody></table> | th | rowheader",
            "<table><tbody><tr><td>Cell</td></tr></tbody></table> | td | cell",
            "<nav>Navigation</nav> | nav | navigation",
            "<main>Main content</main> | main | main",
            "<header>Banner</header> | header | banner",
            "<footer>Footer info</footer> | footer | contentinfo",
            "<form>Form</form> | form | form",
            "<hr /> | hr | separator",
            "<div role='button'>Custom Button</div> | div | button",
            "<span role='dialog'>Custom Dialog</span> | span | dialog",
            "<div role='search'>Custom Search</div> | div | search",
            "<div role='navigation'>Custom Nav</div> | div | navigation",
            "<a role='checkbox'>Custom Checkbox</a> | a | checkbox",
            "<div role='treeitem'>Item 1</div> | div | treeitem",
            "<div role='progressbar'>Progress</div> | div | progressbar"
    })
    @DisplayName("AriaRoles.effectiveRole correctly infers explicit and implicit HTML5 roles")
    void testEffectiveRole(String htmlFragment, String selector, String expectedRole) {
        Document doc = Jsoup.parse(htmlFragment);
        Element el = doc.selectFirst(selector);
        assertThat(el).isNotNull();
        assertThat(AriaRoles.effectiveRole(el)).isEqualTo(expectedRole);
    }

    @Test
    @DisplayName("AriaRoles.effectiveRole returns null for unsemantic elements and anchors without href")
    void testEffectiveRoleNulls() {
        Document doc = Jsoup.parse("<div><span>plain</span><a>no href</a><input type='hidden'/></div>");
        Element div = doc.selectFirst("div");
        Element span = doc.selectFirst("span");
        Element aWithoutHref = doc.selectFirst("a");
        Element hiddenInput = doc.selectFirst("input[type=hidden]");

        assertThat(AriaRoles.effectiveRole(div)).isNull();
        assertThat(AriaRoles.effectiveRole(span)).isNull();
        assertThat(AriaRoles.effectiveRole(aWithoutHref)).isNull();
        assertThat(AriaRoles.effectiveRole(hiddenInput)).isNull();
        assertThat(AriaRoles.effectiveRole(null)).isNull();
    }

    // =========================================================================
    // 2. Parameterized Tests: AccessibleName.compute
    // =========================================================================

    static Stream<Arguments> accessibleNameFixtures() {
        return Stream.of(
                Arguments.of("Direct text in button", "<button>Save Profile</button>", "button", "Save Profile"),
                Arguments.of("Nested child elements in button", "<button><span>Download</span> <b>PDF</b></button>", "button", "Download PDF"),
                Arguments.of("aria-label on button", "<button aria-label='Close dialog'>X</button>", "button", "Close dialog"),
                Arguments.of("aria-label takes precedence over child text", "<button aria-label='Submit form'>Click me</button>", "button", "Submit form"),
                Arguments.of("aria-labelledby with single target", "<div><span id='btn-lbl'>Send Invoice</span><button aria-labelledby='btn-lbl'>Icon</button></div>", "button", "Send Invoice"),
                Arguments.of("aria-labelledby with multiple space-separated targets", "<div><span id='t1'>Delete</span> <span id='t2'>Account</span><button aria-labelledby='t1 t2'>X</button></div>", "button", "Delete Account"),
                Arguments.of("label[for] on input", "<div><label for='email-field'>Email Address</label><input id='email-field' type='text'/></div>", "input", "Email Address"),
                Arguments.of("Enclosing label wrapping input", "<label>Username: <input type='text'/></label>", "input", "Username:"),
                Arguments.of("img alt attribute", "<img src='logo.png' alt='Company Logo'/>", "img", "Company Logo"),
                Arguments.of("input type=submit value attribute", "<input type='submit' value='Send Message'/>", "input", "Send Message"),
                Arguments.of("input placeholder fallback", "<input type='text' placeholder='Search records...'/>", "input", "Search records..."),
                Arguments.of("Whitespace normalization in computed name", "<button>  \n  Multi \t line  \n  text  </button>", "button", "Multi line text")
        );
    }

    @ParameterizedTest(name = "[{index}] {0}: {1} -> expected name: ''{3}''")
    @MethodSource("accessibleNameFixtures")
    @DisplayName("AccessibleName.compute computes exact name following W3C priority rules")
    void testAccessibleNameComputation(String description, String html, String selector, String expectedName) {
        Document doc = Jsoup.parse(html);
        Element el = doc.selectFirst(selector);
        assertThat(el).isNotNull();
        assertThat(AccessibleName.compute(el)).isEqualTo(expectedName);
    }

    // =========================================================================
    // 3. Conservative Rejection on Ambiguous aria-labelledby (§6.5, §9.5)
    // =========================================================================

    @Test
    @DisplayName("AccessibleName and JsoupLocatorTranslator conservatively reject when aria-labelledby target is missing")
    void testConservativeRejectionMissingAriaLabelledBy() {
        // Missing target ID 'non-existent-id'
        String html = """
                <div id="container">
                    <button id="b1" aria-labelledby="non-existent-id">Delete Item</button>
                    <button id="b2" aria-labelledby="valid-header missing-id">Submit</button>
                    <span id="valid-header">Payment</span>
                </div>
                """;

        Document doc = Jsoup.parse(html);
        Element btn1 = doc.getElementById("b1");
        Element btn2 = doc.getElementById("b2");

        // Conservative: computation fails closed and returns null on ambiguous missing references
        assertThat(AccessibleName.compute(btn1))
                .as("b1 points to non-existent target, accessible name must be null")
                .isNull();

        assertThat(AccessibleName.compute(btn2))
                .as("b2 points to partially missing targets, accessible name must be null")
                .isNull();

        // When queried via ROLE_NAME, neither matches the candidate name
        ResolvedLocator loc1 = new ResolvedLocator("e1", LocatorStrategy.ROLE_NAME, "Delete Item", "button");
        Elements matched1 = JsoupLocatorTranslator.query(doc, loc1);
        assertThat(matched1).isEmpty();

        ResolvedLocator loc2 = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Payment Submit", "button");
        Elements matched2 = JsoupLocatorTranslator.query(doc, loc2);
        assertThat(matched2).isEmpty();

        // When queried via LABEL, ambiguous aria-labelledby also yields NO match
        ResolvedLocator labelLoc = new ResolvedLocator("e1", LocatorStrategy.LABEL, "Delete Item");
        Elements matchedLabel = JsoupLocatorTranslator.query(doc, labelLoc);
        assertThat(matchedLabel).isEmpty();
    }

    // =========================================================================
    // 4. Parameterized Tests: All 5 Locator Strategies via JsoupLocatorTranslator.query
    // =========================================================================

    @Test
    @DisplayName("Strategy TEST_ID: matches element by [data-testid]")
    void testQueryTestId() {
        String html = """
                <div>
                    <button data-testid="save-btn">Save</button>
                    <button data-testid="cancel-btn">Cancel</button>
                    <input data-testid="search-input" type="text" />
                </div>
                """;
        Document doc = Jsoup.parse(html);

        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "save-btn");
        Elements elements = JsoupLocatorTranslator.query(doc, loc);

        assertThat(elements).hasSize(1);
        assertThat(elements.first().tagName()).isEqualTo("button");
        assertThat(elements.first().text()).isEqualTo("Save");

        ResolvedLocator missingLoc = new ResolvedLocator("e2", LocatorStrategy.TEST_ID, "non-existent-btn");
        assertThat(JsoupLocatorTranslator.query(doc, missingLoc)).isEmpty();
    }

    @Test
    @DisplayName("Strategy ROLE_NAME: matches element with matching effectiveRole and accessibleName")
    void testQueryRoleName() {
        String html = """
                <main>
                    <button>Submit</button>
                    <a href="/submit">Submit</a>
                    <div role="button">Submit</div>
                    <button aria-label="Submit">Another</button>
                </main>
                """;
        Document doc = Jsoup.parse(html);

        // Should match button tag with text "Submit", div[role=button] with text "Submit", and button with aria-label="Submit"
        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.ROLE_NAME, "Submit", "button");
        Elements elements = JsoupLocatorTranslator.query(doc, loc);

        assertThat(elements).hasSize(3);
        assertThat(elements).extracting(Element::tagName).contains("button", "div");

        // Link with text Submit has role 'link', not 'button'
        ResolvedLocator linkLoc = new ResolvedLocator("e2", LocatorStrategy.ROLE_NAME, "Submit", "link");
        Elements links = JsoupLocatorTranslator.query(doc, linkLoc);
        assertThat(links).hasSize(1);
        assertThat(links.first().tagName()).isEqualTo("a");
    }

    @Test
    @DisplayName("Strategy LABEL: matches controls via label[for], wrapping label, and aria-label")
    void testQueryLabel() {
        String html = """
                <form>
                    <!-- 1. label[for] -->
                    <label for="first-name">First Name</label>
                    <input id="first-name" type="text" />

                    <!-- 2. Wrapping label -->
                    <label>Last Name <input id="last-name" type="text" /></label>

                    <!-- 3. aria-label on input -->
                    <input id="phone" type="tel" aria-label="Phone Number" />

                    <!-- 4. aria-labelledby on input -->
                    <span id="lbl-addr">Billing Address</span>
                    <textarea id="addr" aria-labelledby="lbl-addr"></textarea>
                </form>
                """;
        Document doc = Jsoup.parse(html);

        // 1. label[for]
        ResolvedLocator loc1 = new ResolvedLocator("e1", LocatorStrategy.LABEL, "First Name");
        Elements res1 = JsoupLocatorTranslator.query(doc, loc1);
        assertThat(res1).hasSize(1);
        assertThat(res1.first().id()).isEqualTo("first-name");

        // 2. Wrapping label
        ResolvedLocator loc2 = new ResolvedLocator("e2", LocatorStrategy.LABEL, "Last Name");
        Elements res2 = JsoupLocatorTranslator.query(doc, loc2);
        assertThat(res2).hasSize(1);
        assertThat(res2.first().id()).isEqualTo("last-name");

        // 3. aria-label
        ResolvedLocator loc3 = new ResolvedLocator("e3", LocatorStrategy.LABEL, "Phone Number");
        Elements res3 = JsoupLocatorTranslator.query(doc, loc3);
        assertThat(res3).hasSize(1);
        assertThat(res3.first().id()).isEqualTo("phone");

        // 4. aria-labelledby
        ResolvedLocator loc4 = new ResolvedLocator("e4", LocatorStrategy.LABEL, "Billing Address");
        Elements res4 = JsoupLocatorTranslator.query(doc, loc4);
        assertThat(res4).hasSize(1);
        assertThat(res4.first().id()).isEqualTo("addr");
    }

    @Test
    @DisplayName("Strategy PLACEHOLDER: matches element by [placeholder]")
    void testQueryPlaceholder() {
        String html = """
                <div>
                    <input type="text" placeholder="Enter coupon code" />
                    <textarea placeholder="Enter coupon code"></textarea>
                </div>
                """;
        Document doc = Jsoup.parse(html);

        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.PLACEHOLDER, "Enter coupon code");
        Elements elements = JsoupLocatorTranslator.query(doc, loc);

        assertThat(elements).hasSize(2);
        assertThat(elements).extracting(Element::tagName).containsExactly("input", "textarea");
    }

    @Test
    @DisplayName("Strategy TEXT: matches innermost leaf element with exact text")
    void testQueryExactTextInnermost() {
        String html = """
                <div class="card">
                    <div class="card-header">
                        <span>Card Header</span>
                    </div>
                    <div class="card-body">
                        <p>Welcome to TestGen!</p>
                    </div>
                </div>
                """;
        Document doc = Jsoup.parse(html);

        // Leaf-preference: should match <span>, not the parent <div class="card-header"> or <div class="card">
        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.TEXT, "Card Header");
        Elements elements = JsoupLocatorTranslator.query(doc, loc);

        assertThat(elements).hasSize(1);
        assertThat(elements.first().tagName()).isEqualTo("span");
        assertThat(elements.first().text()).isEqualTo("Card Header");

        // Exact match on paragraph text
        ResolvedLocator pLoc = new ResolvedLocator("e2", LocatorStrategy.TEXT, "Welcome to TestGen!");
        Elements pElements = JsoupLocatorTranslator.query(doc, pLoc);
        assertThat(pElements).hasSize(1);
        assertThat(pElements.first().tagName()).isEqualTo("p");

        // Partial match should NOT match with exact text
        ResolvedLocator partial = new ResolvedLocator("e3", LocatorStrategy.TEXT, "Welcome");
        assertThat(JsoupLocatorTranslator.query(doc, partial)).isEmpty();
    }

    @Test
    @DisplayName("JsoupLocatorTranslator throws on null arguments")
    void testNullValidation() {
        Document doc = Jsoup.parse("<div>test</div>");
        ResolvedLocator loc = new ResolvedLocator("e1", LocatorStrategy.TEST_ID, "btn");

        assertThatThrownBy(() -> JsoupLocatorTranslator.query(null, loc))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> JsoupLocatorTranslator.query(doc, null))
                .isInstanceOf(NullPointerException.class);

        ResolvedLocator nullStrat = new ResolvedLocator("e1", null, "btn");
        assertThatThrownBy(() -> JsoupLocatorTranslator.query(doc, nullStrat))
                .isInstanceOf(NullPointerException.class);
    }
}
