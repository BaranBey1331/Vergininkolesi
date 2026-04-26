const { existsSync } = require('node:fs');
const { spawn } = require('node:child_process');

const jarPath = 'build/libs/vergininkolesi-1.0.0-all.jar';

function run(command, args) {
  const child = spawn(command, args, {
    stdio: 'inherit',
    shell: process.platform === 'win32',
  });

  child.on('exit', (code) => {
    process.exit(code ?? 1);
  });
}

if (!existsSync(jarPath)) {
  console.log('Jar bulunamadi, Gradle build baslatiliyor...');
  run(process.platform === 'win32' ? 'gradlew.bat' : './gradlew', ['build']);
} else {
  console.log('Vergininkolesi baslatiliyor...');
  run('java', ['-jar', jarPath]);
}
