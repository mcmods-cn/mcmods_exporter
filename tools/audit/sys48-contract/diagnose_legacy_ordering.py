#!/usr/bin/env python3
"""Read-only JEI ordering counters, never extract, repair or print documents."""
from pathlib import Path, PurePosixPath
import argparse
import collections
import hashlib
import json
import os
import stat
import sys
import zipfile


def need(ok, reason):
    if not ok:
        raise RuntimeError(reason)


def unique(pairs):
    value = {}
    for key, item in pairs:
        need(key not in value, 'DUPLICATE_JSON_KEY');value[key] = item
    return value


def fingerprint(value):
    return (value.st_dev, value.st_ino, value.st_uid, value.st_gid, value.st_mode,
            value.st_nlink, value.st_size, value.st_mtime_ns, value.st_ctime_ns)


def hash_file(stream):
    stream.seek(0);digest = hashlib.sha256()
    while True:
        block = stream.read(1 << 20)
        if not block:
            break
        digest.update(block)
    return digest.hexdigest()


def diagnose(path):
    before = path.lstat();need(stat.S_ISREG(before.st_mode) and before.st_size <= 256 << 20, 'BOUNDED_REGULAR_ZIP_REQUIRED')
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    counts = {name: collections.Counter() for name in ('index_recipe_summaries', 'recipe_collection_bindings')}
    documents = collections.Counter()
    with os.fdopen(descriptor, 'rb') as stream:
        need(fingerprint(before) == fingerprint(os.fstat(stream.fileno())), 'INPUT_IDENTITY_CHANGED')
        digest = hash_file(stream)
        with zipfile.ZipFile(stream) as archive:
            entries = archive.infolist();need(len(entries) <= 50000 and sum(row.file_size for row in entries) <= 512 << 20, 'ZIP_ENTRY_OR_EXPANDED_BUDGET')
            names = set();selected = []
            for row in entries:
                value = PurePosixPath(row.filename)
                need(not value.is_absolute() and '..' not in value.parts and '\\' not in row.filename and value.parts and ':' not in value.parts[0], 'UNSAFE_ZIP_PATH')
                need(not stat.S_ISLNK(row.external_attr >> 16) and not row.flag_bits & 1, 'ZIP_LINK_OR_ENCRYPTION')
                canonical = '/'.join(value.parts).casefold();need(canonical not in names, 'DUPLICATE_OR_AMBIGUOUS_ZIP_PATH');names.add(canonical)
                if row.filename == 'recipes/jei/categories.json':
                    selected.append(('index_recipe_summaries', row))
                elif row.filename.startswith('recipes/jei/recipes/') and row.filename.endswith('.json'):
                    selected.append(('recipe_collection_bindings', row))
            need(sum(row.file_size for _, row in selected) <= 64 << 20, 'JEI_JSON_TOTAL_BUDGET')
            for family, row in selected:
                need(row.file_size <= 16 << 20 and row.file_size <= max(1, row.compress_size) * 2000, 'JEI_JSON_MEMBER_BUDGET')
                document = json.loads(archive.read(row), object_pairs_hook=unique)
                need(type(document) is dict and type(document.get('recipes')) is list, 'JEI_RECIPES_ARRAY_REQUIRED')
                documents[family] += 1
                for recipe in document['recipes']:
                    need(type(recipe) is dict, 'JEI_RECIPE_OBJECT_REQUIRED')
                    kind = recipe.get('layout_kind')
                    kind = ('ABSENT' if 'layout_kind' not in recipe else
                            kind if kind in ('unknown', 'not_applicable', 'shaped', 'shapeless') else 'OTHER')
                    if 'ordered' not in recipe:
                        ordering = 'ABSENT'
                    elif recipe['ordered'] is None:
                        ordering = 'NULL'
                    elif type(recipe['ordered']) is bool:
                        ordering = 'TRUE' if recipe['ordered'] else 'FALSE'
                    else:
                        ordering = 'INVALID_TYPE'
                    counts[family][kind + ':' + ordering] += 1
        need(hash_file(stream) == digest and fingerprint(before) == fingerprint(os.fstat(stream.fileno())) == fingerprint(path.lstat()), 'INPUT_CHANGED_NO_DIAGNOSTIC_CLAIM')
    return {'scope': 'READ_ONLY_JEI_ORDERING_COUNTERS_NOT_REPAIR_OR_FULL_SCHEMA_VALIDATION',
            'inputSHA256': digest, 'inputBytes': before.st_size, 'documents': dict(documents),
            'countsByDistinctDocumentFamilyDoNotSumAsUniqueRecipes': {name: dict(sorted(rows.items())) for name, rows in counts.items()},
            'zipModified': False, 'extracted': False, 'recipeIdsTitlesBodiesOrPersonalDataPrinted': False,
            'absentOrderingMeaning': 'unknown, never infer false; re-export from source using corrected producer if needed',
            'productionInventoryOrExecutionClaim': False}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__);parser.add_argument('archive', type=Path)
    args = parser.parse_args()
    try:
        print(json.dumps(diagnose(args.archive), indent=2))
    except Exception as error:
        # Decoder/ZIP/OS exception strings can contain member names or bytes.
        print(json.dumps({'state': 'HOLD_NO_DIAGNOSTIC_CLAIM', 'errorType': type(error).__name__,
                          'reasonCode': str(error) if type(error) is RuntimeError else 'INPUT_OR_PARSE_ERROR_VALUES_WITHHELD'}))
        sys.exit(1)
