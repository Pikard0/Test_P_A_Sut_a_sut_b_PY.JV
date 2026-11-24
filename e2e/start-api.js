const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const root = path.resolve(__dirname, '..');
const dependencyClasspath = fs.readFileSync(
  path.join(root, 'target', 'e2e-classpath.txt'),
  'utf8'
).trim();
const classpath = [
  path.join(root, 'target', 'classes'),
  ...dependencyClasspath.split(path.delimiter).filter(Boolean)
].join(path.delimiter);

const child = spawn('java', ['-Duser.timezone=UTC', '-cp', classpath, 'ua.course.rental.RentalApplication'], {
  cwd: root,
  env: process.env,
  stdio: 'inherit'
});

const stop = () => child.kill();
process.on('SIGINT', stop);
process.on('SIGTERM', stop);
child.on('exit', code => process.exit(code ?? 1));
