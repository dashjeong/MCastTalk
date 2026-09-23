'use strict';
// Run only against an owned Java host and a newly generated workspace/account set.
const fs = require('node:fs');
const path = require('node:path');
const { spawn, spawnSync } = require('node:child_process');
const { randomBytes } = require('node:crypto');
const [script, outputArg] = process.argv.slice(2);
const root = process.cwd();
const output = path.resolve(outputArg);
if (!output.startsWith(path.join(root, '.run') + path.sep)) throw new Error('Evidence must stay under source/.run');
if (!/^[a-z-]+\.cjs$/.test(script || '')) throw new Error('Expected a test script basename');
fs.mkdirSync(output, { recursive: true });
const useSavedWorkspace=process.env.MCASTTALK_TEST_USE_SAVED_WORKSPACE==='1';
if(useSavedWorkspace&&!process.env.MCASTTALK_TEST_LAUNCHER)throw new Error('Saved workspace test requires an explicit owned launcher');
const data = useSavedWorkspace ? path.resolve(fs.readFileSync(path.join(path.dirname(process.env.MCASTTALK_TEST_LAUNCHER),'workspace.txt'),'utf8').replace(/^\uFEFF/,'').trim()) : path.join(output, 'data-' + randomBytes(6).toString('hex'));
if(!data.startsWith(path.join(root,'.run')+path.sep))throw new Error('Never initialize a real user workspace');
const java = process.env.MCASTTALK_TEST_JAVA || path.join(root, '.tools/jdk17/jdk-17.0.20.1+1/bin/java.exe');
const lib = path.join(root, 'windows/host/build/install/host/lib/*');
const port=Number(process.env.MCASTTALK_TEST_PORT||8791);
if(!Number.isInteger(port)||port<1024||port>65535)throw new Error('Invalid test port');
const env = { ...process.env, MCASTTALK_TEST_PASSWORD: randomBytes(6).toString('base64'),
  MCASTTALK_TEST_OUTPUT: output, MCASTTALK_TEST_URL: 'http://127.0.0.1:'+port };
const lanIp=process.env.MCASTTALK_TEST_LAN_IP;
if(lanIp)env.MCASTTALK_TEST_URL='https://'+lanIp+':'+port;
env.MCASTTALK_TEST_DATA=data;
const fixture = spawnSync(java, ['-cp', ['windows/host/build/classes/kotlin/test',
  'windows/host/build/classes/kotlin/main', 'windows/host/src/main/resources', lib].join(path.delimiter),
  'app.mcasttalk.windows.host.AccountFixtureMain', data], { env, encoding: 'utf8', windowsHide: true, timeout: 120000 });
if (fixture.status !== 0) throw new Error('Fixture failed: ' + fixture.stderr);
if(process.env.MCASTTALK_TEST_OFFLINE_BUNDLE){
  const install=spawnSync(java,['-cp',['windows/host/build/classes/kotlin/test','windows/host/build/classes/kotlin/main',lib].join(path.delimiter),
    'app.mcasttalk.windows.host.BundleFixtureMain',path.resolve(process.env.MCASTTALK_TEST_OFFLINE_BUNDLE),data],{env,encoding:'utf8',windowsHide:true,timeout:240000});
  if(install.status!==0)throw new Error('Bundle fixture failed: '+install.stderr);
  console.log(install.stdout);
}
if(process.env.MCASTTALK_TEST_ENGINE_CONFIG){
  const config=JSON.parse(fs.readFileSync(path.resolve(process.env.MCASTTALK_TEST_ENGINE_CONFIG),'utf8'));
  config.tempRoot=path.join(data,'temp','engine');
  const configFile=path.join(data,'config','test-engine.json');fs.writeFileSync(configFile,JSON.stringify(config));
  const values={python:process.env.MCASTTALK_TEST_PYTHON||path.join(root,'.tools/media-python/Scripts/python.exe'),workerHome:path.join(root,'windows/worker'),config:configFile};
  fs.writeFileSync(path.join(data,'config/inference.properties'),Object.entries(values).map(([k,v])=>k+'='+v.replaceAll('\\','/')).join('\n'));
}
const instance = JSON.parse(fs.readFileSync(path.join(data, 'config/data-root.json'), 'utf8')).instanceId;
const log = fs.openSync(path.join(output, 'host.log'), 'w');
const socketArgs = process.env.MCASTTALK_TEST_SOCKET_TEMP ? ['-Djdk.net.unixdomain.tmpdir=' + process.env.MCASTTALK_TEST_SOCKET_TEMP] : [];
const launchArgs=[...(useSavedWorkspace?[]:['--data-dir=' + data]),'--port='+port,'--no-browser',...(lanIp?['--bind=0.0.0.0','--lan-hosts='+lanIp]:[])];
const server = spawn(process.env.MCASTTALK_TEST_LAUNCHER||java,process.env.MCASTTALK_TEST_LAUNCHER?launchArgs:[...socketArgs, '-cp', lib, 'app.mcasttalk.windows.host.MainKt',...launchArgs],
  { env, windowsHide: true, stdio: ['ignore', log, log] });
async function main() {
  const start = Date.now();
  try {
    let ready = false;
    for (let i = 0; i < 100; i++) {
      if (server.exitCode !== null) throw new Error('Owned server exited; inspect host.log');
      try {
        const response = lanIp ? await new Promise((resolve,reject)=>{
          const req=require('node:https').get(env.MCASTTALK_TEST_URL+'/health/ready',{ca:fs.readFileSync(path.join(data,'config/tls/MCastTalk-LAN.crt')),timeout:1000},res=>{
            let body='';res.on('data',b=>body+=b);res.on('end',()=>resolve({ok:res.statusCode===200,json:async()=>JSON.parse(body)}));
          });req.on('error',reject);req.on('timeout',()=>req.destroy(new Error('timeout')));
        }) : await fetch(env.MCASTTALK_TEST_URL + '/health/ready', { signal: AbortSignal.timeout(1000) });
        const body=await response.json();
        if (body.instanceId !== instance) throw new Error('PORT_OWNERSHIP_MISMATCH');
        ready = response.ok;
        if (ready) break;
      } catch (error) { if (error.message === 'PORT_OWNERSHIP_MISMATCH') throw error; }
      await new Promise(resolve => setTimeout(resolve, 200));
    }
    if (!ready) throw new Error('Owned server not ready');
    const result = spawnSync(process.execPath, [path.join(root, 'windows/host/tests', script)],
      { env, encoding: 'utf8', windowsHide: true, timeout: 300000, maxBuffer: 10 * 1024 * 1024 });
    const text = (result.stdout || '') + (result.stderr || '') + (result.error ? result.error.message : '');
    fs.writeFileSync(path.join(output, 'browser.log'), text);
    fs.writeFileSync(path.join(output, 'execution.json'), JSON.stringify({ script, startedAt: new Date(start).toISOString(),
      elapsedMs: Date.now() - start, exitCode: result.status, ok: result.status === 0,
      environment: 'Windows host, fresh temporary accounts and headless Edge; not a clean VM or human UAT' }, null, 2));
    console.log(text);
    process.exitCode = result.status === 0 ? 0 : 1;
  } finally {
    // Only the Java child created above, never an installed/user-owned host.
    const closed = new Promise(resolve => server.once('exit', resolve));
    if (server.exitCode === null) {
      // Windows force-termination skips JVM hooks. End this exact owned child tree too.
      if(process.platform==='win32')spawnSync('taskkill.exe',['/PID',String(server.pid),'/T','/F'],{windowsHide:true,stdio:'ignore'});
      else server.kill();
      await closed;
    }
    fs.closeSync(log);
    delete env.MCASTTALK_TEST_PASSWORD;
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
