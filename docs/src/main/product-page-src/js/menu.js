export default () => {
  function hide(element) {
    element.style.display = "none";
  }

  function hideMany(elements) {
    for (let i = 0; i < elements.length; i++) {
      elements[i].style.display = "none";
    }
  }

  function show(element) {
    element.style.display = "block";
  }

  function grabElement(id) {
    return document.getElementById(id);
  }

  function onClick(elementId, action) {
    let element = document.getElementById(elementId);
    element.addEventListener("click", action);
  }

  // mobile menu
  let productsSubmenu = grabElement("products-submenu");
  let getStartedSubmenu = grabElement("getting-started-submenu");
  let devSubmenu = grabElement("dev-submenu");
  let menu = grabElement("menu-container");

  function closeAllMobile() {
    hideMany([productsSubmenu, getStartedSubmenu, devSubmenu]);
  }

  function toggleMenu(element) {
    return function () {
      let isShown = element.style.display === "block";

      if (isShown) {
        hide(element);
      } else {
        show(element);
      }
    };
  }

  function expandItem(item) {
    return function () {
      closeAllMobile();
      show(item);
    };
  }

  onClick("menu-close-button", function () {
    return hide(menu);
  });

  onClick("menu-icon", toggleMenu(menu));
  onClick("products-button", expandItem(productsSubmenu));
  onClick("getting-started-button", expandItem(getStartedSubmenu));
  onClick("dev-button", expandItem(devSubmenu));

  // desktop menu
  let header = grabElement("header");
  let products = grabElement("products-dropdown");
  let getStarted = grabElement("getting-started-dropdown");
  let dev = grabElement("dev-dropdown");

  header.addEventListener("mouseleave", closeAll());

  function closeAll() {
    hideMany([products, getStarted, dev]);
  }

  function setDropdown(triggerId, dropdown) {
    let trigger = document.getElementById(triggerId);

    trigger.addEventListener("mouseover", function () {
      closeAll();
      show(dropdown);
    });

    dropdown.addEventListener("mouseleave", function () {
      return hide(dropdown);
    });
  }

  setDropdown("products-nav-item-button", products);
  setDropdown("getting-started-nav-item-button", getStarted);
  setDropdown("dev-nav-item-button", dev);
};
