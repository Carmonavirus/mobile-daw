// Projects and imported sounds live in the browser's IndexedDB (per site, per browser).
import { type Project } from './model/project';

const DB_NAME = 'mobile-daw';
const PROJECTS = 'projects';
const SAMPLES = 'samples';

let dbPromise: Promise<IDBDatabase> | null = null;

function db(): Promise<IDBDatabase> {
  if (!dbPromise) {
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open(DB_NAME, 1);
      req.onupgradeneeded = () => {
        const d = req.result;
        if (!d.objectStoreNames.contains(PROJECTS)) d.createObjectStore(PROJECTS);
        if (!d.objectStoreNames.contains(SAMPLES)) d.createObjectStore(SAMPLES);
      };
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }
  return dbPromise;
}

function run<T>(store: string, mode: IDBTransactionMode, fn: (s: IDBObjectStore) => IDBRequest): Promise<T> {
  return db().then(
    (d) =>
      new Promise<T>((resolve, reject) => {
        const tx = d.transaction(store, mode);
        const req = fn(tx.objectStore(store));
        tx.oncomplete = () => resolve(req.result as T);
        tx.onerror = () => reject(tx.error);
        tx.onabort = () => reject(tx.error);
      }),
  );
}

export interface StoredProject {
  name: string;
  json: string;
  modified: number;
}

/** Same rule as ProjectStore.sanitize on Android, so names map to the same files. */
export const fileName = (name: string) => name.replace(/[^A-Za-z0-9 _-]/g, '_').trim() || 'Untitled';

export async function listProjects(): Promise<StoredProject[]> {
  const all = await run<StoredProject[]>(PROJECTS, 'readonly', (s) => s.getAll());
  return all.sort((a, b) => b.modified - a.modified);
}

export function saveProject(project: Project): Promise<unknown> {
  localStorage.setItem('last_project', project.name);
  const value: StoredProject = { name: project.name, json: JSON.stringify(project, null, 1), modified: Date.now() };
  return run(PROJECTS, 'readwrite', (s) => s.put(value, fileName(project.name)));
}

export async function loadProject(name: string): Promise<string | null> {
  const v = await run<StoredProject | undefined>(PROJECTS, 'readonly', (s) => s.get(fileName(name)));
  return v?.json ?? null;
}

export function deleteProject(name: string): Promise<unknown> {
  return run(PROJECTS, 'readwrite', (s) => s.delete(fileName(name)));
}

export async function projectExists(name: string): Promise<boolean> {
  const v = await run<StoredProject | undefined>(PROJECTS, 'readonly', (s) => s.get(fileName(name)));
  return !!v;
}

export async function uniqueName(base: string): Promise<string> {
  if (!(await projectExists(base))) return base;
  let i = 2;
  while (await projectExists(`${base} ${i}`)) i++;
  return `${base} ${i}`;
}

export const lastOpened = () => {
  try {
    return localStorage.getItem('last_project');
  } catch {
    return null;
  }
};

export function saveSampleBytes(path: string, bytes: ArrayBuffer): Promise<unknown> {
  return run(SAMPLES, 'readwrite', (s) => s.put(bytes, path));
}

export async function loadSampleBytes(path: string): Promise<ArrayBuffer | null> {
  const v = await run<ArrayBuffer | undefined>(SAMPLES, 'readonly', (s) => s.get(path));
  return v ?? null;
}
