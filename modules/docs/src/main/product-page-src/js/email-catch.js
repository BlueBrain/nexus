import $ from "jquery";

function serializeFormData($form) {
  return $form.serializeArray().reduce((obj, item) => {
    obj[item.name] = item.value;
    return obj;
  }, {});
}

// keys of data must match exactly column headers in gSheets
function submitForm(api, data) {
  return new Promise((resolve, reject) => {
    $.post(api, data)
      .done(resolve)
      .fail(reject);
  });
}

function renderPending($form) {
  $form.html(`
    <div class="pending">
      <span>Sending...</span>
    </div>
  `);
}

function renderFulfilled($form) {
  $form.html(`
    <div class="fulfilled">
      <span>Thanks! We'll keep you up to date.</span>
    </div>
  `);
}

function renderFailed($form) {
  $form.html(`
    <div class="failed">
      <span>Oh, bother! We failed to save your email!</span>
    </div>
  `);
}

export default (apiPath, $form) => {
  $form.on('submit', function (e) {
    e.preventDefault();
    let data = serializeFormData($(this));
    renderPending($form);
    submitForm(apiPath, data)
      .then(() => renderFulfilled($form))
      .catch(() => renderFailed($form));
  });
};
