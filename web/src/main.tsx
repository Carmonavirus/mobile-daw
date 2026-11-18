import { render } from 'preact';
import { startEngine } from './audio/engine';
import * as s from './store';
import './styles.css';
import { App } from './ui/App';

render(<App />, document.getElementById('app')!);
startEngine();
s.boot();

// Handy for checking things from the browser console (and the automated tests).
import * as engine from './audio/engine';
(window as unknown as { daw: unknown }).daw = { store: s, engine };
