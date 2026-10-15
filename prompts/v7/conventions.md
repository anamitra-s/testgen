# Playwright-Java Coding Conventions (v7)

Follow these strict code conventions when authoring or refactoring Page Objects and tests (§6.11, §6.12):

## Page Object Conventions
- Package: Place page objects in `com.company.pages` or the area-specific package specified by `ExistingCodeContext`.
- Constructor: Accept `Page page` as the sole constructor argument and assign to a private final field.
- Locators: Store locators in private final `Locator` fields initialized via `page.getBy...` using the exact expressions from `ResolvedLocatorSet`.
- Action Methods: Public methods representing user actions (e.g. `loginAs(username, password)`, `selectDateRange(from, to)`).
- Chaining: Action methods that navigate to a new page should return the new Page Object instance. Action methods on the same page return `this`.
- No Assertions in Page Objects: Assertion statements belong exclusively in test classes.

## Test Class Conventions
- Package: Place test classes in `com.company.tests`.
- Annotations: `@Test`, `@DisplayName("descriptive business test name")`.
- Structure: Follow Arrange-Act-Assert structure clearly.
- Error Handling: Do not swallow exceptions in tests.
