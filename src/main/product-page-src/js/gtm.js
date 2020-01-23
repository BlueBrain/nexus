// Add Google Tag Manager and CookieConsent Widget

const tag = "GTM-NDSVJ4D";

const setUpCookiesWidget = () => {
  const firstStyle = document.getElementsByTagName("link")[0];
  const firstScript = document.getElementsByTagName("script")[0];
  const cookieConsentStyle = document.createElement("link");
  cookieConsentStyle.rel = "stylesheet";
  cookieConsentStyle.type = "text/css";
  cookieConsentStyle.href =
    "//cdnjs.cloudflare.com/ajax/libs/cookieconsent2/3.1.0/cookieconsent.min.css";
  const cookieConsentScript = document.createElement("script");
  cookieConsentScript.src =
    "//cdnjs.cloudflare.com/ajax/libs/cookieconsent2/3.1.0/cookieconsent.min.js";
  firstScript.parentNode.insertBefore(cookieConsentScript, firstScript);
  firstStyle.parentNode.insertBefore(cookieConsentStyle, firstStyle);
};

const enableTracking = () => {
  window["ga-disable-" + tag + "-1"] = false;

  var layerVar = "dataLayer";
  window[layerVar].push({ "gtm.start": new Date().getTime(), event: "gtm.js" });
  var f = document.getElementsByTagName("script")[0];
  var j = document.createElement("script");
  var dl = layerVar != "dataLayer" ? "&l=" + layerVar : "";
  j.async = true;
  j.src = "https://www.googletagmanager.com/gtm.js?id=" + tag + dl;
  f.parentNode.insertBefore(j, f);
};

const disableTracking = () => {
  window["ga-disable-" + tag + "-1"] = true;
};

const initCookieConsentWidget = () => {
  window.cookieconsent.initialise({
    palette: {
      popup: {
        background: "#252e39"
      },
      button: {
        background: "#14a7d0"
      }
    },
    position: "top",
    static: true,
    type: "opt-out",
    content: {
      message:
        "By continuing your browsing on this site, you agree to the use of cookies to improve your user experience and to make statistics of visits.",
      dismiss: "OK",
      link: "Read the legal notice",
      href:
        "https://www.epfl.ch/about/overview/overview/regulations-and-guidelines/"
    },
    onInitialise: function(status) {
      var didConsent = this.hasConsented();
      if (didConsent) {
        enableTracking();
      }
      if (!didConsent) {
        disableTracking();
      }
    },
    onStatusChange: function(status, chosenBefore) {
      var didConsent = this.hasConsented();
      if (didConsent) {
        enableTracking();
      }
      if (!didConsent) {
        disableTracking();
        location.reload();
      }
    },
    onRevokeChoice: function() {
      var type = this.options.type;
      if (type == "opt-in") {
        disableTracking();
      }
      if (type == "opt-out") {
        enableTracking();
      }
    }
  });
};

setUpCookiesWidget();
disableTracking();
window.dataLayer = window.dataLayer || [];

window.addEventListener("load", () => {
  initCookieConsentWidget();
});
