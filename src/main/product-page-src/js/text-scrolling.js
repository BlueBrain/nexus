const EMPHASIS_WORDS = [
  "science",
  "industry",
  "astronomy",
  "research",
  "companies",
  "biotech"
];

const EMPHASIS_TEXT_DELAY_MS = 3500;

const chars = ["$", "%", "#", "@", "&", "(", ")", "=", "*", "/", "_"];
const charsTotal = chars.length;
const getRandomInt = (min, max) =>
  Math.floor(Math.random() * (max - min + 1)) + min;

function setCharAt(str, index, chr) {
  if (index > str.length - 1) {
    let diff = index - (str.length - 1);
    str = str.padEnd(index + 1);
    return setCharAt(str, index, chr);
  }
  return str.substr(0, index) + chr + str.substr(index + 1);
}

class ScrollText {
  constructor(el) {
    this.el = el;
    this.textIndex = EMPHASIS_WORDS.indexOf(el.innerHTML);
  }

  get currentText() {
    return EMPHASIS_WORDS[this.textIndex];
  }

  get nextText() {
    return this.textIndex + 1 >= EMPHASIS_WORDS.length
      ? EMPHASIS_WORDS[0]
      : EMPHASIS_WORDS[this.textIndex + 1];
  }

  get elmText () {
    let elem = document.createElement('textarea');
    elem.innerHTML = this.el.innerHTML;
    return elem.value;
  }

  wrapIncrement () {
    if (this.textIndex + 1 >= EMPHASIS_WORDS.length) {
      return this.textIndex = 0;
    }
    return this.textIndex++;
  }

  setText (pos, letter) {
    let oldText = this.elmText;
    let newString = setCharAt(oldText, pos, letter);
    this.el.innerHTML = newString
  }

  scroll() {
    for (let pos = 0; pos <= this.currentText.length; pos++) {
      let letter = this.nextText[pos];
      setTimeout(() => {
        let newChar = chars[getRandomInt(0, charsTotal - 1)];
        // set random character at position
        this.setText(pos, newChar);
        // wait a bit then set new character at pos
        setTimeout(() => {
          this.setText(pos, letter || ' ');
        }, 100);
      }, pos * 80);
    }
    this.wrapIncrement();
  }

  cycle() {
    return setInterval(() => this.scroll(), EMPHASIS_TEXT_DELAY_MS);
  }
}

export default ScrollText;
