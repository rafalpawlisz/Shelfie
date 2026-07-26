// Security-rules tests for the household model. Run from this directory:
//   npm test
// (wraps `firebase emulators:exec` so the Firestore emulator hosts the run).
import { readFileSync } from "node:fs";
import { after, before, beforeEach, describe, it } from "node:test";
import {
  assertFails,
  assertSucceeds,
  initializeTestEnvironment,
} from "@firebase/rules-unit-testing";
import {
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  collection,
  setDoc,
  updateDoc,
  writeBatch,
} from "firebase/firestore";

let env;

before(async () => {
  env = await initializeTestEnvironment({
    projectId: "demo-shelfie",
    firestore: { rules: readFileSync(new URL("../firestore.rules", import.meta.url), "utf8") },
  });
});

after(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

const ANNA = "uid-anna";
const BOB = "uid-bob";
const EVE = "uid-eve";

function db(uid) {
  return env.authenticatedContext(uid).firestore();
}

function anonDb() {
  return env.unauthenticatedContext().firestore();
}

// Seed data bypassing the rules.
async function seedHousehold(hid, members, code) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const f = ctx.firestore();
    await setDoc(doc(f, "households", hid), {
      name: "Dom",
      members,
      inviteCode: code,
      // Seeds may be memberless (an emptied household kept for recovery).
      createdBy: Object.keys(members)[0] ?? ANNA,
    });
    await setDoc(doc(f, "inviteCodes", code), { householdId: hid });
  });
}

describe("users/{uid}", () => {
  it("owner reads and writes their own doc", async () => {
    await assertSucceeds(setDoc(doc(db(ANNA), "users", ANNA), { householdId: "h1" }));
    await assertSucceeds(getDoc(doc(db(ANNA), "users", ANNA)));
  });

  it("someone else's doc is off limits", async () => {
    await assertFails(setDoc(doc(db(BOB), "users", ANNA), { householdId: "h1" }));
    await assertFails(getDoc(doc(db(BOB), "users", ANNA)));
  });

  it("unauthenticated access fails", async () => {
    await assertFails(getDoc(doc(anonDb(), "users", ANNA)));
  });
});

describe("households: create", () => {
  it("creator as sole member succeeds", async () => {
    await assertSucceeds(
      setDoc(doc(db(ANNA), "households", "h1"), {
        name: "Dom",
        members: { [ANNA]: true },
        inviteCode: "CODE01",
        createdBy: ANNA,
      }),
    );
  });

  it("creating with someone else in members fails", async () => {
    await assertFails(
      setDoc(doc(db(ANNA), "households", "h1"), {
        name: "Dom",
        members: { [ANNA]: true, [BOB]: true },
        inviteCode: "CODE01",
        createdBy: ANNA,
      }),
    );
  });
});

describe("households: read", () => {
  it("member reads, stranger and anonymous do not", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertSucceeds(getDoc(doc(db(ANNA), "households", "h1")));
    await assertFails(getDoc(doc(db(EVE), "households", "h1")));
    await assertFails(getDoc(doc(anonDb(), "households", "h1")));
  });
});

describe("households: joining", () => {
  it("a stranger may add exactly their own uid", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertSucceeds(
      updateDoc(doc(db(BOB), "households", "h1"), { [`members.${BOB}`]: true }),
    );
  });

  it("adding someone else's uid fails", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertFails(
      updateDoc(doc(db(BOB), "households", "h1"), { [`members.${EVE}`]: true }),
    );
  });

  it("joining while also renaming fails", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertFails(
      updateDoc(doc(db(BOB), "households", "h1"), {
        [`members.${BOB}`]: true,
        name: "Przejęte",
      }),
    );
  });
});

describe("households: leaving", () => {
  it("a member may remove exactly their own uid", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01");
    await assertSucceeds(
      updateDoc(doc(db(BOB), "households", "h1"), {
        members: { [ANNA]: true },
      }),
    );
  });

  it("kicking another member fails", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01");
    await assertFails(
      updateDoc(doc(db(BOB), "households", "h1"), {
        members: { [BOB]: true },
      }),
    );
  });
});

describe("households: emptied but kept (recovery by invite code)", () => {
  it("a signed-in user may join a memberless household", async () => {
    // What makes "everyone left" recoverable: the household survives empty
    // and whoever holds the code can rejoin it.
    await seedHousehold("h1", {}, "CODE01");
    await assertSucceeds(getDoc(doc(db(BOB), "inviteCodes", "CODE01")));
    await assertSucceeds(
      updateDoc(doc(db(BOB), "households", "h1"), { [`members.${BOB}`]: true }),
    );
    // ...and then reads its data again.
    await assertSucceeds(getDoc(doc(db(BOB), "households/h1/products", "p1")));
  });

  it("nobody can delete a memberless household", async () => {
    // hasOnly() is trivially true for an empty members map — the delete rule
    // must also require actual membership.
    await seedHousehold("h1", {}, "CODE01");
    await assertFails(deleteDoc(doc(db(EVE), "households", "h1")));
  });

  it("a memberless household stays unreadable until someone joins", async () => {
    await seedHousehold("h1", {}, "CODE01");
    await assertFails(getDoc(doc(db(EVE), "households", "h1")));
    await assertFails(getDoc(doc(db(EVE), "households/h1/products", "p1")));
  });
});

describe("households: rename and delete", () => {
  it("a member renames; a stranger does not", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertSucceeds(updateDoc(doc(db(ANNA), "households", "h1"), { name: "Chata" }));
    await assertFails(updateDoc(doc(db(EVE), "households", "h1"), { name: "Chata" }));
  });

  it("only the last remaining member may delete", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01");
    await assertFails(deleteDoc(doc(db(ANNA), "households", "h1")));
    await seedHousehold("h2", { [ANNA]: true }, "CODE02");
    await assertSucceeds(deleteDoc(doc(db(ANNA), "households", "h2")));
  });
});

describe("household subcollections (synced pantry data)", () => {
  it("a member reads and writes; a stranger and anonymous do not", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    const productDoc = { name: "Milk", quantity: 2, updatedAt: 111 };
    await assertSucceeds(
      setDoc(doc(db(ANNA), "households/h1/products", "p1"), productDoc),
    );
    await assertSucceeds(getDoc(doc(db(ANNA), "households/h1/products", "p1")));
    await assertSucceeds(deleteDoc(doc(db(ANNA), "households/h1/products", "p1")));
    await assertFails(
      setDoc(doc(db(EVE), "households/h1/products", "p2"), productDoc),
    );
    await assertFails(getDoc(doc(db(EVE), "households/h1/products", "p1")));
    await assertFails(getDoc(doc(anonDb(), "households/h1/products", "p1")));
  });

  it("membership is checked per household, not globally", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await seedHousehold("h2", { [BOB]: true }, "CODE02");
    // Bob is a member somewhere — just not of h1.
    await assertFails(
      setDoc(doc(db(BOB), "households/h1/items", "i1"), { listId: "l1" }),
    );
  });
});

describe("inviteCodes", () => {
  it("any signed-in user resolves a known code; anonymous does not", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertSucceeds(getDoc(doc(db(EVE), "inviteCodes", "CODE01")));
    await assertFails(getDoc(doc(anonDb(), "inviteCodes", "CODE01")));
  });

  it("listing codes is denied even for signed-in users", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertFails(getDocs(collection(db(ANNA), "inviteCodes")));
  });

  it("household creation batch may create its code; a stranger may not", async () => {
    // The realistic write: household + code + user pointer in one batch.
    const anna = db(ANNA);
    const batch = writeBatch(anna);
    batch.set(doc(anna, "households", "h1"), {
      name: "Dom",
      members: { [ANNA]: true },
      inviteCode: "CODE01",
      createdBy: ANNA,
    });
    batch.set(doc(anna, "inviteCodes", "CODE01"), { householdId: "h1" });
    batch.set(doc(anna, "users", ANNA), { householdId: "h1" });
    await assertSucceeds(batch.commit());

    // Eve tries to plant a code pointing at Anna's household.
    await assertFails(
      setDoc(doc(db(EVE), "inviteCodes", "EVIL01"), { householdId: "h1" }),
    );
  });

  it("the last member's cleanup batch deletes household and code together", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    const anna = db(ANNA);
    const batch = writeBatch(anna);
    batch.delete(doc(anna, "households", "h1"));
    batch.delete(doc(anna, "inviteCodes", "CODE01"));
    await assertSucceeds(batch.commit());

    // A stranger cannot delete a live code.
    await seedHousehold("h2", { [ANNA]: true }, "CODE02");
    await assertFails(deleteDoc(doc(db(EVE), "inviteCodes", "CODE02")));
  });
});
