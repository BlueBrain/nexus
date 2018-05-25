import lines from "./lines";
import svgify from "./libs/svg";
import emailCatch from "./email-catch";
import ScrollText from "./text-scrolling";

svgify();
lines(".lines");
// strange jquery setup to avoid
// package shinnanigans
const jquery = require("jquery");
window.$ = window.jQuery = jquery;
require("jquery-ui-dist/jquery-ui.js");
require("jquery-modal");
const $ = jquery;

const EMAIL_CATCH_API_URL =
  "https://script.google.com/macros/s/AKfycbyShA-fZV1b_eYb0UNAwr1FZGwGjTzeGa-QKlomEeVlrQSJhA4/exec";

const CONTACT_FORM_API_URL =
  "https://script.google.com/macros/s/AKfycbyShA-fZV1b_eYb0UNAwr1FZGwGjTzeGa-QKlomEeVlrQSJhA4/exec";

$(function() {
  const ScienceScroll = new ScrollText(document.querySelector('#emphasis'));
  ScienceScroll.cycle();

  emailCatch(EMAIL_CATCH_API_URL, $("#email-catch"));
  emailCatch(CONTACT_FORM_API_URL, $("#contact-catch"));

  $(".tab-container").tabs();

  $(".tab-container .menu-item").on("click", function() {
    let $elm = $(this);
    $elm.addClass("current-menu-item");
    $elm.siblings().each(function() {
      let $sib = $(this);
      $sib.removeClass("current-menu-item");
    });
  });
  $(window).on("load resize", resetMenu);
});

const resetMenu = () => {
  let $left = $(".current-menu-item").position().left;
  let $width = $(".current-menu-item").outerWidth(true);
  $(".wee").css({ left: $left, width: $width });

  $(".menu-item").hover(
    function() {
      let $left = $(this).position().left;
      let $width = $(this).outerWidth(true);
      $(".wee").css({ left: $left, width: $width });
    },
    function() {
      let $left = $(".current-menu-item").position().left;
      let $width = $(".current-menu-item").outerWidth(true);
      $(".wee").css({ left: $left, width: $width });
    }
  );
};
