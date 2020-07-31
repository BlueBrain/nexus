import emailCatch from "./email-catch";
import attachMenu from "./menu";

const EMAIL_CATCH_API_URL =
  "https://script.google.com/macros/s/AKfycbzG21hsMSsWiPa5fDd6IbPzrfPvZKVf0Xy7eJ4RmxWh38VHJIQ/exec";
function main() {
  attachMenu();
  document.getElementById("email-catch") &&
    emailCatch(EMAIL_CATCH_API_URL, document.getElementById("email-catch"));
}

window.onload = main;
