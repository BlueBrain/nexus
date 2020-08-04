//
// Copy files to paradox for scala to build
//

const path = require("path");
const glob = require("glob");
const fs = require("fs");

const SOURCE_DIR = "../paradox/public/*.html";
const OUT_DIR = "../paradox/";

const findFilesWithWildcard = (sourceDir) =>
  new Promise((resolve, reject) => {
    glob(path.resolve(__dirname, sourceDir), {}, (error, files) => {
      if (error) return reject(error);
      return resolve(files);
    });
  });

const copyFile = (outDir) => (filePath) =>
  new Promise((resolve, reject) => {
    const fileName = path.basename(filePath);
    fs.copyFile(
      filePath,
      path.resolve(__dirname, outDir + fileName),
      (error) => {
        if (error) return reject(error);
        return resolve(fileName);
      }
    );
  });

findFilesWithWildcard(SOURCE_DIR)
  .then((files) => Promise.all(files.map((file) => copyFile(OUT_DIR)(file))))
  .then((fileNamesCopied) =>
    console.log(`${fileNamesCopied.join(", ")} all copied`)
  )
  .catch(console.log);
