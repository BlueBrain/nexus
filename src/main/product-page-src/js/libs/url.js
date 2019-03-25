import qs from 'querystring';

/**
 * Get an object with key:value pairs from URL query string.
 *
 * @param {string} querystring
 */
export function getAllUrlParams(querystring) {
  if (querystring.startsWith('?')) {
    querystring = querystring.slice(1);
  }
  return qs.decode(querystring);
}

export function removeTokenFromUrl(location) {
  const [url, paramsString] = location.href.split('?');
  if (paramsString === undefined) {
    return;
  }
  const params = getAllUrlParams(paramsString);
  const { access_token, ...updatedParamsMap } = params;
  let updatedParams = qs.encode(updatedParamsMap);
  updatedParams = updatedParams.length ? `?${updatedParams}` : '';
  const appLocation = `${url}${updatedParams}`;
  window.history.replaceState({}, document.title, appLocation);
  return access_token;
}
