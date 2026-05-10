/*
 * Lightweight offline fallback for Quick Link extraction.
 *
 * The app only needs the small subset of Mozilla Readability's interface used by
 * QuickLinkViewModel: new Readability(documentClone).parse().
 */
(function () {
  function textLength(node) {
    return (node && node.innerText ? node.innerText.trim().length : 0);
  }

  function findMainContent(document) {
    var candidates = Array.prototype.slice.call(
      document.querySelectorAll("article, main, [role='main'], .article, .post, .content, body")
    );

    candidates.sort(function (a, b) {
      return textLength(b) - textLength(a);
    });

    return candidates[0] || document.body;
  }

  function clean(node) {
    var selectors = [
      "script",
      "style",
      "noscript",
      "iframe",
      "nav",
      "header",
      "footer",
      "form",
      "button",
      "aside",
      "[aria-hidden='true']"
    ];

    selectors.forEach(function (selector) {
      Array.prototype.slice.call(node.querySelectorAll(selector)).forEach(function (child) {
        child.remove();
      });
    });
  }

  window.Readability = function Readability(document) {
    this.document = document;
  };

  window.Readability.prototype.parse = function parse() {
    var document = this.document;
    var title = document.title || "Quick Link";
    var main = findMainContent(document);
    if (!main) return null;

    var clone = main.cloneNode(true);
    clean(clone);

    return {
      title: title.trim() || "Quick Link",
      content: clone.innerHTML || document.body.innerHTML || ""
    };
  };
})();
