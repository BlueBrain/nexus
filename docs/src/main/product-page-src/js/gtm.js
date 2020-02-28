// Add Google Tag Manager and CookieConsent Widget

const GTM_TAG = "GTM-NDSVJ4D";
const DATA_LAYER = "dataLayer";

const enableTracking = () => {
  window[DATA_LAYER] = window[DATA_LAYER] || [];
  window["ga-disable-" + GTM_TAG + "-1"] = false;
  window[DATA_LAYER].push({
    "gtm.start": new Date().getTime(),
    event: "gtm.js"
  });
  const f = document.getElementsByTagName("script")[0];
  const j = document.createElement("script");
  const dl = DATA_LAYER != "dataLayer" ? "&l=" + DATA_LAYER : "";
  j.async = true;
  j.src = "https://www.googletagmanager.com/gtm.js?id=" + GTM_TAG + dl;
  f.parentNode.insertBefore(j, f);
};

const disableTracking = () => {
  window[DATA_LAYER] = window[DATA_LAYER] || [];
  window["ga-disable-" + GTM_TAG + "-1"] = true;
};

window.addEventListener("load", () => {
  enableTracking();
});
