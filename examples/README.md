# Example alignments

Copied unchanged from BEAST 2.8 (`beast-base/src/test/resources/beast.base/examples/`), so these are
the datasets you already know from BEAUti and the BEAST tutorials.

Pass one on the command line, or drop it onto the Partitions table:

```sh
bin/phylospec-ui examples/Primates.nex
```

## Contemporaneous data

Taxon names carry no sampling times, so leave **Tip Dates** switched off.

| File | Taxa | Sites | Notes |
|---|---:|---:|---|
| `Primates.nex` | 12 | 898 | Primate mtDNA — the standard BEAUti starter |
| `dna.nex` | 10 | 705 | Small and quick; `dna.fasta` is the same data |
| `anolis.nex` | 29 | 1456 | *Anolis* lizards; a good size for comparing tree priors |

## Serially sampled data

Taxon names carry a sampling year. Switch **Tip Dates** on and choose *Regular expression*, since the
year sits at the end of the name rather than in a fixed `_`-separated position. The default pattern
`(\d+\.?\d*)$` already picks it up:

| File | Taxa | Sites | Names look like | Year read as |
|---|---:|---:|---|---|
| `Flu.nex` | 21 | 1698 | `TREESPARROW_HENAN_1_2004` | `2004` |
| `Dengue4.env.nex` | 17 | 1485 | `D4Brazi82` | `82` |
| `RSV2.nex` | 129 | 629 | `BE8078s92` | `92` |

`Dengue4.env.nex` and `RSV2.nex` use two-digit years, so they come out as 82 and 92 rather than 1982
and 1992. PhyloSpec's `parse(...)` extracts a number and does not offer an offset, so the dates are
shifted by 1900 — which is harmless here, because every taxon in each file shifts by the same
amount and only the differences between sampling times matter.

## Amino acid data

| File | Taxa | Sites | Notes |
|---|---:|---:|---|
| `aminoacid.fasta` | 10 | 234 | Use a protein substitution model — `wag`, `jtt` or `lg` |

## Other formats

| File | Taxa | Sites | Notes |
|---|---:|---:|---|
| `dna.fasta` | 10 | 705 | Exercises the `fromFasta` loader |
