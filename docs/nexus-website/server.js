const http = require('http');
const querystring = require('querystring');
const nodemailer = require('nodemailer');

const server = http.createServer().listen(3000);

server.on('request', (req, res) => {
  var body = '';
  req.on('data', data => {
    body += data;
  });

  req.on('end', () => {
    if (req.url === '/send') {
      const post = querystring.parse(body);
      sendMail(post);
    }
    res.writeHead(200, {'Content-Type': 'text/plain'});
    res.end("success");
  });
});

function sendMail({email, name, message}) {
  return nodemailer.createTestAccount((err, account) => {

    // create reusable transporter object using the default SMTP transport
    let transporter = nodemailer.createTransport({
      host: 'smtp.ethereal.email',
      port: 587,
      secure: false, // true for 465, false for other ports
      auth: {
          user: account.user, // generated ethereal user
          pass: account.pass  // generated ethereal password
      }
    });

    // setup email data with unicode symbols
    const mailOptions = {
      from: `${name} <${email}>`, // sender address
      to: 'bbp-nexus-dev@groupes.epfl.ch', // list of receivers
      subject: 'Contact Form', // Subject line
      text: message, // plain text body
      html: `<b>${message}</b>` // html body
    };

    // send mail with defined transport object
    transporter.sendMail(mailOptions, (error, info) => {
      if (error) {
          return console.log(error);
      }
      console.log('Message sent: %s', info.messageId);
      // Preview only available when sending through an Ethereal account
      console.log('Preview URL: %s', nodemailer.getTestMessageUrl(info));
    });
  });
}
console.log('Listening on port 3000');
