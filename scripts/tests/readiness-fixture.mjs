import http from 'node:http';
let mode='retry', attempts=0;
http.createServer((request,response)=>{
  request.resume();
  response.setHeader('Content-Type','application/json');
  if(request.url==='/control/unauthorized'){mode='unauthorized';response.end('{}');return;}
  if(request.url==='/control/stats'){response.end(JSON.stringify({attempts}));return;}
  if(request.url==='/api/auth/login'){
    attempts++;
    if(mode==='unauthorized'){response.writeHead(401);response.end('{}');return;}
    if(attempts===1){response.writeHead(502);response.end('{}');return;}
    response.end(JSON.stringify({token:'synthetic-fixture-token'}));return;
  }
  if(request.url==='/api/patients' && request.headers.authorization==='Bearer synthetic-fixture-token'){
    response.end('[]');return;
  }
  response.writeHead(404);response.end('{}');
}).listen(34004,'127.0.0.1',()=>console.log('READY'));
