"use strict";
var pattern = /[\f\n\r\t\v ]{2,}/g;
var replacement = " ";



function normalize(str)
{
	return str.replace(pattern, replacement);
}



module.exports = normalize;
