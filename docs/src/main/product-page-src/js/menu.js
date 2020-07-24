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

const productsDropdow = document.getElementById("products-nav-item-button");
productsDropdow.addEventListener("click", toggle("products-dropdown"));
