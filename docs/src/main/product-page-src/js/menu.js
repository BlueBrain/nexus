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

const productsDropdow = document.getElementById("products-nav-item-button");

productsDropdow.addEventListener("mouseover", function () {
  const element = document.getElementById("products-dropdown");
  element.style.display = "block";
});

productsDropdow.addEventListener("mouseout", function () {
  const element = document.getElementById("products-dropdown");
  element.style.display = "none";
});

const useCasesDropdown = document.getElementById("use-cases-nav-item-button");
useCasesDropdown.addEventListener("mouseover", toggle("use-cases-dropdown"));

const getStartedDropdown = document.getElementById(
  "getting-started-nav-item-button"
);
getStartedDropdown.addEventListener(
  "mouseover",
  toggle("getting-started-dropdown")
);

const devDropdown = document.getElementById("dev-nav-item-button");
devDropdown.addEventListener("mouseover", toggle("dev-dropdown"));
