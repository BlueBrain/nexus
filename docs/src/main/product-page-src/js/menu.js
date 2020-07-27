// mobile menu
function toggle(elementId) {
  let isShown = false;

  return function () {
    const element = document.getElementById(elementId);

    isShown = !isShown;

    if (isShown) {
      element.style.display = "block";
    } else {
      element.style.display = "none";
    }
  };
}

const productsButton = document.getElementById("products-button");
productsButton.addEventListener("click", toggle("products-submenu"));

const menuButton = document.getElementById("menu-icon");
menuButton.addEventListener("click", toggle("menu-container"));

const useCasesButton = document.getElementById("use-cases-button");
useCasesButton.addEventListener("click", toggle("use-cases-submenu"));

const gettingStartedButton = document.getElementById("getting-started-button");
gettingStartedButton.addEventListener(
  "click",
  toggle("getting-started-submenu")
);

const devButton = document.getElementById("dev-button");
devButton.addEventListener("click", toggle("dev-submenu"));

// desktop menu
const header = document.getElementById("header");
const products = document.getElementById("products-dropdown");
const useCases = document.getElementById("use-cases-dropdown");
const getStarted = document.getElementById("getting-started-dropdown");
const dev = document.getElementById("dev-dropdown");

header.addEventListener("mouseleave", closeAll());

function closeAll() {
  products.style.display = "none";
  useCases.style.display = "none";
  getStarted.style.display = "none";
  dev.style.display = "none";
}

function setDropdown(triggerId, dropdown) {
  const trigger = document.getElementById(triggerId);

  trigger.addEventListener("mouseover", function () {
    closeAll();
    dropdown.style.display = "block";
  });

  dropdown.addEventListener("mouseleave", function () {
    dropdown.style.display = "none";
  });
}

setDropdown("products-nav-item-button", products);
setDropdown("use-cases-nav-item-button", useCases);
setDropdown("getting-started-nav-item-button", getStarted);
setDropdown("dev-nav-item-button", dev);
