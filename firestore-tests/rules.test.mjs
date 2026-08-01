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
  deleteField,
  doc,
  getDoc,
  getDocs,
  collection,
  serverTimestamp,
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
async function seedHousehold(hid, members, code, extra = {}) {
  await env.withSecurityRulesDisabled(async (ctx) => {
    const f = ctx.firestore();
    await setDoc(doc(f, "households", hid), {
      name: "Dom",
      members,
      inviteCode: code,
      // Seeds may be memberless (an emptied household kept for recovery).
      createdBy: Object.keys(members)[0] ?? ANNA,
      ...extra,
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

  it("a stranger cannot replace the members map with just themselves", async () => {
    // The blunt version of hijacking: send a whole new map that drops the
    // existing member. membersDiffOnlySelf() is what stops it.
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertFails(
      updateDoc(doc(db(BOB), "households", "h1"), { members: { [BOB]: true } }),
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

  it("leaving takes the leaver's own activity stamp with it", async () => {
    // It has to happen in this write: a former member may not touch the
    // activity map at all, so an entry left behind is there for good.
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01", {
      memberActivity: { [ANNA]: new Date("2026-01-01"), [BOB]: new Date("2026-01-02") },
    });
    const household = doc(db(BOB), "households", "h1");
    await assertSucceeds(
      updateDoc(household, {
        [`members.${BOB}`]: deleteField(),
        [`memberActivity.${BOB}`]: deleteField(),
      }),
    );
    await env.withSecurityRulesDisabled(async (ctx) => {
      const snap = await getDoc(doc(ctx.firestore(), "households", "h1"));
      // Anna's stamp is untouched; only the leaver's is gone.
      if (Object.keys(snap.data().memberActivity).join() !== ANNA) {
        throw new Error(`unexpected memberActivity: ${JSON.stringify(snap.data().memberActivity)}`);
      }
    });
  });

  it("leaving with a stamp that was never written is fine", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01");
    await assertSucceeds(
      updateDoc(doc(db(BOB), "households", "h1"), {
        [`members.${BOB}`]: deleteField(),
        [`memberActivity.${BOB}`]: deleteField(),
      }),
    );
  });

  it("leaving may not clear anyone else's activity", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01", {
      memberActivity: { [ANNA]: new Date("2026-01-01"), [BOB]: new Date("2026-01-02") },
    });
    const household = doc(db(BOB), "households", "h1");
    await assertFails(
      updateDoc(household, {
        [`members.${BOB}`]: deleteField(),
        [`memberActivity.${ANNA}`]: deleteField(),
      }),
    );
    await assertFails(
      updateDoc(household, { [`members.${BOB}`]: deleteField(), memberActivity: {} }),
    );
    // Leaving is not an occasion to stamp yourself as active either.
    await assertFails(
      updateDoc(household, {
        [`members.${BOB}`]: deleteField(),
        [`memberActivity.${BOB}`]: serverTimestamp(),
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

  it("a member stamps lastActiveAt; a stranger does not", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertSucceeds(
      updateDoc(doc(db(ANNA), "households", "h1"), { lastActiveAt: serverTimestamp() }),
    );
    await assertFails(
      updateDoc(doc(db(EVE), "households", "h1"), { lastActiveAt: serverTimestamp() }),
    );
  });

  it("lastActiveAt must be the server's clock too", async () => {
    // Same reasoning as the per-member stamp: a value a member picks makes a
    // live household look abandoned, and this one speaks for all of them.
    await seedHousehold("h1", { [ANNA]: true }, "CODE01", {
      lastActiveAt: new Date("2026-01-01"),
    });
    const household = doc(db(ANNA), "households", "h1");
    await assertFails(updateDoc(household, { lastActiveAt: new Date("2020-01-01") }));
    await assertFails(updateDoc(household, { lastActiveAt: "recently" }));
    await assertFails(updateDoc(household, { lastActiveAt: deleteField() }));
    await assertSucceeds(updateDoc(household, { lastActiveAt: serverTimestamp() }));
  });

  it("a member stamps their own memberActivity entry, alone or with lastActiveAt", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01");
    await assertSucceeds(
      updateDoc(doc(db(ANNA), "households", "h1"), {
        [`memberActivity.${ANNA}`]: serverTimestamp(),
      }),
    );
    // What the app actually writes: both stamps in one update.
    await assertSucceeds(
      updateDoc(doc(db(BOB), "households", "h1"), {
        lastActiveAt: serverTimestamp(),
        [`memberActivity.${BOB}`]: serverTimestamp(),
      }),
    );
  });

  it("nobody stamps or clears activity for someone else", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01", {
      memberActivity: { [BOB]: new Date("2026-01-01") },
    });
    // Forging a fellow member's activity would hide a dead membership entry;
    // clearing it does the opposite and makes a live member look prunable.
    await assertFails(
      updateDoc(doc(db(ANNA), "households", "h1"), {
        [`memberActivity.${BOB}`]: serverTimestamp(),
      }),
    );
    await assertFails(
      updateDoc(doc(db(ANNA), "households", "h1"), {
        [`memberActivity.${BOB}`]: deleteField(),
      }),
    );
    await assertFails(updateDoc(doc(db(ANNA), "households", "h1"), { memberActivity: {} }));
    await assertFails(
      updateDoc(doc(db(EVE), "households", "h1"), {
        [`memberActivity.${EVE}`]: serverTimestamp(),
      }),
    );
  });

  it("activity must be the server's clock, not a chosen value", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    const household = doc(db(ANNA), "households", "h1");
    // Backdating your own entry is how you would fake being inactive; the
    // value has to be the time the write is served.
    await assertFails(
      updateDoc(household, { [`memberActivity.${ANNA}`]: new Date("2020-01-01") }),
    );
    await assertFails(updateDoc(household, { [`memberActivity.${ANNA}`]: "recently" }));
    await assertSucceeds(
      updateDoc(household, { [`memberActivity.${ANNA}`]: serverTimestamp() }),
    );
  });

  it("a member records their own version alongside the stamps", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01");
    // What the app actually writes: both stamps and the version in one update.
    await assertSucceeds(
      updateDoc(doc(db(BOB), "households", "h1"), {
        lastActiveAt: serverTimestamp(),
        [`memberActivity.${BOB}`]: serverTimestamp(),
        [`memberVersions.${BOB}`]: "0.2.1 (6)",
      }),
    );
    await assertSucceeds(
      updateDoc(doc(db(ANNA), "households", "h1"), {
        [`memberVersions.${ANNA}`]: "1.0 (1)",
      }),
    );
  });

  it("nobody writes a version for someone else", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01", {
      memberVersions: { [BOB]: "0.2.0 (5)" },
    });
    // The field exists to show which phone is behind; writing another
    // member's entry would let a stale one be dressed up as current.
    await assertFails(
      updateDoc(doc(db(ANNA), "households", "h1"), {
        [`memberVersions.${BOB}`]: "0.2.1 (6)",
      }),
    );
    await assertFails(
      updateDoc(doc(db(ANNA), "households", "h1"), {
        [`memberVersions.${BOB}`]: deleteField(),
      }),
    );
    await assertFails(updateDoc(doc(db(ANNA), "households", "h1"), { memberVersions: {} }));
    await assertFails(
      updateDoc(doc(db(EVE), "households", "h1"), {
        [`memberVersions.${EVE}`]: "0.2.1 (6)",
      }),
    );
  });

  it("a version is a short string, not a smuggled payload", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    const household = doc(db(ANNA), "households", "h1");
    await assertFails(updateDoc(household, { [`memberVersions.${ANNA}`]: 6 }));
    await assertFails(updateDoc(household, { [`memberVersions.${ANNA}`]: "x".repeat(33) }));
    await assertSucceeds(updateDoc(household, { [`memberVersions.${ANNA}`]: "x".repeat(32) }));
  });

  it("leaving takes the leaver's own version with it", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01", {
      memberActivity: { [ANNA]: new Date("2026-01-01"), [BOB]: new Date("2026-01-02") },
      memberVersions: { [ANNA]: "0.2.1 (6)", [BOB]: "0.2.0 (5)" },
    });
    const household = doc(db(BOB), "households", "h1");
    // Same trap as the stamp: after this write there is no membership left to
    // authorise touching the map.
    await assertFails(
      updateDoc(household, {
        [`members.${BOB}`]: deleteField(),
        [`memberVersions.${ANNA}`]: deleteField(),
      }),
    );
    await assertSucceeds(
      updateDoc(household, {
        [`members.${BOB}`]: deleteField(),
        [`memberActivity.${BOB}`]: deleteField(),
        [`memberVersions.${BOB}`]: deleteField(),
      }),
    );
    await env.withSecurityRulesDisabled(async (ctx) => {
      const snap = await getDoc(doc(ctx.firestore(), "households", "h1"));
      if (Object.keys(snap.data().memberVersions).join() !== ANNA) {
        throw new Error(`unexpected memberVersions: ${JSON.stringify(snap.data().memberVersions)}`);
      }
    });
  });

  it("a rename must produce a real name", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    const household = doc(db(ANNA), "households", "h1");
    await assertFails(updateDoc(household, { name: deleteField() }));
    await assertFails(updateDoc(household, { name: "" }));
    await assertFails(updateDoc(household, { name: { sneaky: true } }));
    await assertSucceeds(updateDoc(household, { name: "Chata" }));
  });

  it("a member cannot change createdBy", async () => {
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertFails(
      updateDoc(doc(db(ANNA), "households", "h1"), { createdBy: EVE }),
    );
  });

  it("a member cannot change the invite code", async () => {
    // Guards the update clauses staying narrow: none of them covers this.
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertFails(
      updateDoc(doc(db(ANNA), "households", "h1"), { inviteCode: "STOLEN" }),
    );
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

  it("a member cannot mint an extra code for their own household", async () => {
    // The backdoor this closes: mint a private code, leave cleanly, walk back
    // in later. Nobody could see it (codes cannot be listed) or revoke it.
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    await assertFails(
      setDoc(doc(db(ANNA), "inviteCodes", "MYSECRET"), { householdId: "h1" }),
    );
  });

  it("a code whose household is gone can be cleared by anyone", async () => {
    // Orphans are unresolvable, so every membership check on them fails
    // forever; leaving them undeletable would also leave a hijack waiting for
    // the day that household id is reused.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "inviteCodes", "ORPHAN"), { householdId: "gone" });
    });

    await assertSucceeds(deleteDoc(doc(db(EVE), "inviteCodes", "ORPHAN")));
  });

  it("a sole member can run the whole cleanup in the app's order", async () => {
    // The order deleteHousehold() uses: subcollection documents first, while
    // membership still grants access to them, then code + household + pointer.
    await seedHousehold("h1", { [ANNA]: true }, "CODE01");
    const anna = db(ANNA);
    await env.withSecurityRulesDisabled(async (ctx) => {
      const f = ctx.firestore();
      await setDoc(doc(f, "households", "h1", "products", "p1"), { name: "Mleko" });
      await setDoc(doc(f, "households", "h1", "items", "i1"), { productId: "p1" });
      await setDoc(doc(f, "users", ANNA), { householdId: "h1" });
    });

    await assertSucceeds(deleteDoc(doc(anna, "households", "h1", "products", "p1")));
    await assertSucceeds(deleteDoc(doc(anna, "households", "h1", "items", "i1")));

    const batch = writeBatch(anna);
    batch.delete(doc(anna, "inviteCodes", "CODE01"));
    batch.delete(doc(anna, "households", "h1"));
    batch.delete(doc(anna, "users", ANNA));
    await assertSucceeds(batch.commit());
  });

  it("cleanup is refused to a member who is not alone", async () => {
    await seedHousehold("h1", { [ANNA]: true, [BOB]: true }, "CODE01");
    // Taking the shared data away is not one member's call, and the rules —
    // not just the UI — are what enforce that.
    await assertFails(deleteDoc(doc(db(ANNA), "households", "h1")));
    // Its documents are fair game for either member, though: that is ordinary
    // pantry editing, and this is why deletion must come with sole membership.
    await env.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "households", "h1", "products", "p1"), { name: "Mleko" });
    });
    await assertSucceeds(deleteDoc(doc(db(BOB), "households", "h1", "products", "p1")));
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
