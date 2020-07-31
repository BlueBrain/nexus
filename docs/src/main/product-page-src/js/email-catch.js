function serializeFormData(form) {
  return Array.from(form.elements).reduce((obj, field) => {
    if (field.value) {
      obj[field.name] = field.value;
    }
    return obj;
  }, {});
}

function submitForm(api, data) {
  return fetch(`${api}?email=${data.email}`);
}

function renderPending(form) {
  form.classList.add("pending");
}

function renderFulfilled(form) {
  return () => {
    form.classList.remove("pending");
    form.classList.add("success");
  };
}

function renderFailed(form) {
  return (error) => {
    form.classList.remove("pending");
    form.classList.add("error");
    console.error(error);
  };
}

export default (apiPath, form) => {
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    let data = serializeFormData(form);
    renderPending(form);
    submitForm(apiPath, data)
      .then(renderFulfilled(form))
      .catch(renderFailed(form));
  });
};
